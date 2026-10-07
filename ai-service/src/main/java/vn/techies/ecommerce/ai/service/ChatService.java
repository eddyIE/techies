package vn.techies.ecommerce.ai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import vn.techies.ecommerce.ai.api.dto.ChatDtos.ChatRequest;
import vn.techies.ecommerce.ai.api.dto.ChatDtos.Message;
import vn.techies.ecommerce.ai.api.dto.ChatDtos.ProductCard;
import vn.techies.ecommerce.ai.api.dto.ChatDtos.ProductsEvent;
import vn.techies.ecommerce.ai.api.dto.ChatDtos.SearchQuery;
import vn.techies.ecommerce.ai.client.CatalogClient;
import vn.techies.ecommerce.ai.client.GeminiClient;
import vn.techies.ecommerce.ai.client.InventoryClient;
import vn.techies.ecommerce.ai.config.GeminiProperties;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Runs one chat turn.
 *
 * <p>A turn that triggers a search takes at least two Gemini calls: the model asks for
 * {@code search_products} and stops, this service runs the real search against
 * catalog-service, then the model writes its answer from the results. The model never
 * reaches the catalogue itself.
 *
 * <p>One stream can request several searches at once, and the answer to those can be another
 * request for a search — "so sánh X với Y" does exactly this. So the turn is a loop: stream,
 * run whatever was asked for, hand every result back, repeat. Every requested call must be
 * answered, because the interaction stays at {@code requires_action} until they all are and a
 * model still waiting on a result never writes a word.
 *
 * <p>Products are emitted as their own event rather than being formatted by the model. The
 * app renders real cards that navigate to the PDP, and the model cannot invent a price that
 * disagrees with the catalogue.
 */
@Service
@RequiredArgsConstructor
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    /**
     * How many times in one turn the model may be given tool results.
     *
     * <p>The cap is the only thing stopping a confused model bouncing searches back and forth
     * until the SSE timeout — which, unlike the hang this loop replaced, really is a timeout.
     * The request that carries the last round's results declares no tools, so the model has
     * nothing left to call and has to answer with what it has.
     */
    private static final int MAX_TOOL_ROUNDS = 3;

    /** Said when the model emitted no text at all, so {@code done} never follows silence. */
    private static final String NO_TEXT_WITH_PRODUCTS = "Dạ, em gửi anh/chị vài mẫu phù hợp ạ.";
    private static final String NO_TEXT_FALLBACK =
            "Dạ, em chưa trả lời được câu này. Anh/chị hỏi lại giúp em ạ.";

    private final GeminiClient gemini;
    private final CatalogClient catalog;
    private final InventoryClient inventory;
    private final SystemPromptBuilder promptBuilder;
    private final GeminiProperties properties;
    private final ObjectMapper json;

    /** Callbacks the controller turns into SSE events. */
    public interface ChatListener {
        void onToken(String text);

        void onToolStart(String tool);

        void onProducts(ProductsEvent products);

        void onDone(String finishReason);

        void onError(String code, String message);
    }

    public void chat(ChatRequest request, ChatListener listener) {
        CatalogClient.ProductDetail product;
        try {
            product = catalog.getProduct(request.productId());
        } catch (Exception ex) {
            log.warn("Could not load product {} for chat: {}", request.productId(), ex.toString());
            listener.onError("PRODUCT_NOT_FOUND", "Không tìm thấy sản phẩm này.");
            return;
        }

        Integer stock = null;
        try {
            stock = inventory.getStock(request.productId()).available();
        } catch (Exception ex) {
            // Stock is useful context but not essential; the prompt says "không rõ".
            log.debug("Stock unavailable for {}: {}", request.productId(), ex.toString());
        }

        String systemPrompt = promptBuilder.build(product, stock);
        List<Map<String, Object>> input = toInput(request.messages());

        List<CatalogClient.Category> categories;
        try {
            categories = catalog.categories();
        } catch (Exception ex) {
            log.debug("Category list unavailable, searching by keyword only: {}", ex.toString());
            categories = List.of();
        }

        try {
            runTurn(systemPrompt, input, categories, listener);
        } catch (Exception ex) {
            log.error("Chat turn failed for product {}", request.productId(), ex);
            listener.onError("SERVICE_UNAVAILABLE",
                    "Trợ lý tạm thời không khả dụng. Vui lòng thử lại sau.");
        }
    }

    private void runTurn(String systemPrompt, List<Map<String, Object>> input,
                         List<CatalogClient.Category> categories, ChatListener listener) {
        TurnState state = new TurnState();
        List<Map<String, Object>> tools = tools(categories);
        Map<String, Object> request = gemini.firstRequest(systemPrompt, input, tools);
        int rounds = 0;
        boolean toolStartSent = false;
        // Set once the request in hand declares no tools, so whatever it answers is final.
        boolean lastChance = false;

        while (true) {
            state.startRound();
            gemini.stream(request, event -> handleEvent(event, listener, state));
            List<PendingCall> requested = state.requestedCalls();
            log.debug("Round {}: status={} functionCalls={} calls={}",
                    rounds + 1, state.lastStatus, state.functionCalls, requested);

            if (state.errored) {
                // handleEvent already sent the error event; the turn is over.
                return;
            }
            if (requested.isEmpty()) {
                break;
            }
            if (lastChance) {
                // It was offered no tools and asked anyway. Answering would restart the loop,
                // which is the runaway the cap exists to stop.
                log.warn("Model asked for {} more tool call(s) past the cap; ignoring them",
                        requested.size());
                break;
            }

            // ---- the model asked to search ---------------------------------------------
            // Exactly one spinner per turn however many searches it asks for, across however
            // many rounds: the app shows a single indicator and keys nothing on the name.
            if (!toolStartSent) {
                listener.onToolStart(GeminiClient.SEARCH_PRODUCTS);
                toolStartSent = true;
            }

            if (state.interactionId == null || state.interactionId.isBlank()) {
                // Without the interaction id the continuation cannot be chained.
                flushProducts(state, listener);
                listener.onError("SERVICE_UNAVAILABLE", "Trợ lý tạm thời không khả dụng.");
                return;
            }

            List<GeminiClient.ToolResult> results;
            try {
                results = runSearches(requested, state, categories);
            } catch (Exception ex) {
                log.warn("Product search failed mid-turn: {}", ex.toString());
                flushProducts(state, listener);
                listener.onError("SERVICE_UNAVAILABLE", "Không thể tìm sản phẩm lúc này.");
                return;
            }

            rounds++;
            lastChance = rounds >= MAX_TOOL_ROUNDS;
            if (lastChance) {
                log.warn("Tool-call cap of {} rounds reached; the next request declares no "
                        + "tools so the model answers with what it already has", MAX_TOOL_ROUNDS);
            }
            request = gemini.toolResultRequest(state.interactionId, results, systemPrompt,
                    lastChance ? List.of() : tools);
        }

        // A reply that never produced a word still owes the app its cards.
        flushProducts(state, listener);
        if (!state.textEmitted) {
            log.warn("Turn produced no reply text: status={} functionCalls={} toolRounds={}",
                    state.lastStatus, state.functionCalls, rounds);
            // `done` straight after zero tokens leaves the app waiting for text that is never
            // coming, so the turn always says something, even if only this.
            listener.onToken(state.productsSent ? NO_TEXT_WITH_PRODUCTS : NO_TEXT_FALLBACK);
        }
        listener.onDone("stop");
    }

    /**
     * Sends the cards, at most once per turn.
     *
     * <p>They are held back until the reply has started arriving. Running the search finishes
     * a whole Gemini call before the answer begins, so emitting them the moment they were
     * fetched put the cards on screen while the bubble was still empty — the app looked like
     * it had answered with products and no words.
     */
    private static void flushProducts(TurnState state, ChatListener listener) {
        if (state.productsSent || state.cards.isEmpty()) {
            return;
        }
        state.productsSent = true;
        // `total` is the number of cards sent, not the catalogue's match count. The search
        // routinely matches more than the popup shows, and a count above the cards on screen
        // reads as missing products whether the reply says it or a "see all 23" button does.
        // `query` is echoed instead, so that button can open the product list screen, which
        // does its own paging and states its own total honestly.
        listener.onProducts(new ProductsEvent(
                state.cards.size(), state.query, List.copyOf(state.cards.values())));
    }

    /**
     * The tools offered for a turn: our catalogue search, plus Google Search when enabled.
     *
     * <p>Only the first is ours to run. Google executes the second itself, which is why a
     * grounded answer costs no extra round trip here.
     */
    private List<Map<String, Object>> tools(List<CatalogClient.Category> categories) {
        List<Map<String, Object>> tools = new ArrayList<>();
        tools.add(GeminiClient.searchProductsTool(
                categories.stream().map(CatalogClient.Category::name).toList()));
        if (properties.webSearch()) {
            tools.add(GeminiClient.webSearchTool());
        }
        return tools;
    }

    /** Maps one Gemini stream event onto the listener. */
    private void handleEvent(JsonNode event, ChatListener listener, TurnState state) {
        String type = event.path("event_type").asText();

        switch (type) {
            case "interaction.created", "interaction.completed" -> {
                String id = event.path("interaction").path("id").asText(null);
                if (id != null && !id.isBlank() && state.interactionId == null) {
                    state.interactionId = id;
                }
                if ("interaction.completed".equals(type)) {
                    state.lastStatus = event.path("interaction").path("status").asText("");
                    log.debug("Interaction {} completed with status {}", id, state.lastStatus);
                }
            }
            case "step.delta" -> {
                JsonNode delta = event.path("delta");
                String deltaType = delta.path("type").asText();
                if ("text".equals(deltaType)) {
                    String text = delta.path("text").asText("");
                    if (!text.isEmpty()) {
                        state.textEmitted = true;
                        listener.onToken(text);
                        // After the token, so the reply visibly leads and the cards follow it.
                        flushProducts(state, listener);
                    }
                } else if ("arguments_delta".equals(deltaType)) {
                    // Tool arguments stream in fragments, exactly like reply text. step.start
                    // announces the call with an EMPTY arguments object, so reading only that
                    // yields a search with no criteria.
                    //
                    // The fragment belongs to the call at the event's own index. Appending
                    // every fragment to one buffer is what produced malformed JSON like
                    // {"keyword":"Neo QLED"}{"keyword":"OLED"}, of which Jackson parses the
                    // first object and silently drops the rest.
                    state.call(callIndex(event)).arguments += delta.path("arguments").asText("");
                }
                // thought_signature is internal bookkeeping; ignored.
            }
            case "step.start" -> {
                JsonNode step = event.path("step");
                if ("function_call".equals(step.path("type").asText())) {
                    state.functionCalls++;
                    int index = callIndex(event);
                    PendingCall call = state.call(index);
                    String id = step.path("id").asText("");
                    if (!call.id.isEmpty() && !call.id.equals(id)) {
                        // Two calls at one index would concatenate their arguments, which is
                        // the bug the index exists to prevent. Worth knowing about loudly.
                        log.warn("Two function calls share index {}: {} then {}",
                                index, call.id, id);
                    }
                    call.id = id;
                    call.name = step.path("name").asText("");
                    // arguments are NOT taken from here: step.start carries an empty object
                    // and the real values arrive as arguments_delta fragments.
                }
            }
            case "error" -> {
                String message = event.path("error").path("message").asText("");
                log.warn("Gemini stream error: {}", message);
                state.errored = true;
                flushProducts(state, listener);
                listener.onError(mapErrorCode(message), userFacingError(message));
            }
            default -> {
                // Unknown event types are ignored on purpose: the API adds them over time and
                // an unrecognised event is not a reason to fail a turn.
            }
        }
    }

    /**
     * Which concurrent call an event belongs to.
     *
     * <p>Every event of a stream carries a top-level {@code index}, and for a parallel pair it
     * is the one field that tells the two apart.
     */
    private static int callIndex(JsonNode event) {
        return event.path("index").asInt(0);
    }

    /**
     * Runs every search the model asked for and packs one result per call.
     *
     * <p>A result is produced for each requested call, including one this service cannot run:
     * an unanswered call leaves the interaction waiting forever.
     */
    private List<GeminiClient.ToolResult> runSearches(List<PendingCall> calls, TurnState state,
                                                      List<CatalogClient.Category> categories)
            throws Exception {
        List<SearchResult> searches = new ArrayList<>();
        for (PendingCall call : calls) {
            searches.add(GeminiClient.SEARCH_PRODUCTS.equals(call.name)
                    ? runSearch(call.arguments, categories)
                    : null);
        }

        // Several searches, one `products` event. The lists are interleaved before the cap so
        // a comparison keeps a card from each side of it rather than filling the popup with
        // the first search's matches, then deduped by id — "so sánh" frequently asks two
        // overlapping questions — and finally capped at what the popup can show.
        state.mergeCards(interleave(searches), firstQuery(searches), properties.maxProducts());
        if (state.productsSent) {
            log.debug("Cards arrived after the products event was already sent; not resent");
        }

        List<GeminiClient.ToolResult> results = new ArrayList<>();
        for (int i = 0; i < calls.size(); i++) {
            PendingCall call = calls.get(i);
            SearchResult search = searches.get(i);
            if (search == null) {
                log.warn("Model asked for an unknown tool '{}'; answering that it has none",
                        call.name);
                results.add(new GeminiClient.ToolResult(call.id, call.name,
                        "{\"error\":\"unknown tool\"}"));
                continue;
            }
            // Only the cards that survived the merge are described, so the model is never told
            // about a product the customer cannot see.
            List<ProductCard> shown = search.cards().stream()
                    .filter(c -> state.cards.containsKey(c.id()))
                    .toList();
            results.add(new GeminiClient.ToolResult(call.id, call.name, resultForModel(shown)));
        }
        return results;
    }

    /** Takes one card from each search in turn, so every search is represented. */
    private static List<ProductCard> interleave(List<SearchResult> searches) {
        List<ProductCard> merged = new ArrayList<>();
        int longest = searches.stream()
                .mapToInt(s -> s == null ? 0 : s.cards().size())
                .max().orElse(0);
        for (int i = 0; i < longest; i++) {
            for (SearchResult search : searches) {
                if (search != null && i < search.cards().size()) {
                    merged.add(search.cards().get(i));
                }
            }
        }
        return merged;
    }

    /** The query echoed to the app. One event carries one query, so the first search wins. */
    private static SearchQuery firstQuery(List<SearchResult> searches) {
        return searches.stream()
                .filter(s -> s != null)
                .map(SearchResult::query)
                .findFirst()
                .orElse(null);
    }

    /** Runs one real catalogue search. */
    private SearchResult runSearch(String argumentsJson, List<CatalogClient.Category> categories)
            throws Exception {
        JsonNode args = json.readTree(argumentsJson.isBlank() ? "{}" : argumentsJson);

        String keyword = args.path("keyword").asText(null);
        BigDecimal minPrice = args.hasNonNull("minPrice") ? args.get("minPrice").decimalValue() : null;
        BigDecimal maxPrice = args.hasNonNull("maxPrice") ? args.get("maxPrice").decimalValue() : null;
        // The model returns e.g. "price_asc"; the catalogue enum is PRICE_ASC. An unknown
        // value would 400 the search, so fall back rather than pass it through.
        String rawSort = args.path("sort").asText("NEWEST");
        String sort = switch (rawSort.toUpperCase()) {
            case "PRICE_ASC", "PRICE_DESC", "NAME_ASC", "NEWEST" -> rawSort.toUpperCase();
            default -> "NEWEST";
        };

        // Map the category name the model chose onto its real id.
        UUID categoryId = null;
        String categoryName = args.path("category").asText(null);
        if (categoryName != null && !categoryName.isBlank()) {
            categoryId = categories.stream()
                    .filter(c -> c.name().equalsIgnoreCase(categoryName))
                    .map(CatalogClient.Category::id)
                    .findFirst()
                    .orElse(null);
        }

        log.info("Assistant searching: keyword={} category={} minPrice={} maxPrice={} sort={}",
                keyword, categoryName, minPrice, maxPrice, sort);

        CatalogClient.ProductPage page = catalog.search(
                keyword, categoryId, minPrice, maxPrice, sort, 0, properties.maxProducts());

        List<ProductCard> cards = new ArrayList<>();
        for (CatalogClient.ProductSummary p : page.content()) {
            cards.add(new ProductCard(p.id(), p.name(), p.price(), p.thumbnailUrl()));
        }
        return new SearchResult(new SearchQuery(keyword, categoryId, minPrice, maxPrice, sort),
                cards);
    }

    /** What one search tells the model: only what the customer can actually see. */
    private String resultForModel(List<ProductCard> cards) throws Exception {
        // The model is told only what is on screen. Given a wider total it announced "có 8
        // mẫu" above five cards, which reads as four missing products rather than a capped
        // display.
        //
        // Stock is joined in here and nowhere else in the search path: catalog-service does not
        // carry it, so without this the assistant happily recommends something sold out — and
        // "rẻ nhất" sorts the most likely-sold-out item straight to the top. It is given to the
        // model only; the cards are unchanged, so the app's contract is untouched.
        Map<String, Object> forModel = new LinkedHashMap<>();
        forModel.put("shown", cards.size());
        List<Map<String, Object>> items = new ArrayList<>();
        for (ProductCard c : cards) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", c.name());
            item.put("price", c.price());
            item.put("stock", stockLabel(c.id()));
            items.add(item);
        }
        forModel.put("products", items);
        // Restated here as well as in the system instruction. This payload is the last thing
        // the model reads before writing, and it is the instruction it was most prone to drop.
        forModel.put("instruction",
                "Các sản phẩm này ĐÃ được ứng dụng hiển thị cho khách dưới dạng thẻ bấm được. "
                        + "KHÔNG liệt kê lại tên/giá, KHÔNG mô tả tính năng. Chỉ nói về đúng "
                        + "số sản phẩm đang hiển thị ở trên, TUYỆT ĐỐI KHÔNG nêu một con số "
                        + "lớn hơn hay nhắc rằng còn mẫu khác chưa hiển thị. Mời khách hỏi "
                        + "tiếp. Chỉ gợi ý mẫu còn hàng.");
        return json.writeValueAsString(forModel);
    }

    /**
     * Whether a searched product can actually be bought, in words the model can repeat.
     *
     * <p>At most {@code max-products} of these per turn, so the extra calls are few. If
     * inventory is unreachable the answer is "không rõ" rather than a guess — claiming stock
     * we cannot verify is the failure this exists to prevent.
     */
    private String stockLabel(UUID productId) {
        try {
            return inventory.getStock(productId).inStock() ? "còn hàng" : "hết hàng";
        } catch (Exception ex) {
            log.debug("Stock unavailable for searched product {}: {}", productId, ex.toString());
            return "không rõ";
        }
    }

    /** Trims history to the configured limit and maps it to the API's input shape. */
    private List<Map<String, Object>> toInput(List<Message> messages) {
        int limit = Math.max(1, properties.maxHistoryMessages());
        List<Message> recent = messages.size() <= limit
                ? messages
                : messages.subList(messages.size() - limit, messages.size());

        List<Map<String, Object>> input = new ArrayList<>();
        for (Message m : recent) {
            if ("assistant".equalsIgnoreCase(m.role())) {
                input.add(Map.of("type", "model_output",
                        "content", List.of(Map.of("type", "text", "text", m.content()))));
            } else {
                input.add(Map.of("type", "text", "text", m.content()));
            }
        }
        return input;
    }

    private static String mapErrorCode(String message) {
        String lower = message == null ? "" : message.toLowerCase();
        if (lower.contains("rate limit") || lower.contains("too_many_requests")) {
            return "RATE_LIMITED";
        }
        return "SERVICE_UNAVAILABLE";
    }

    /** Provider wording is for the logs; the customer gets Vietnamese. */
    private static String userFacingError(String message) {
        return "RATE_LIMITED".equals(mapErrorCode(message))
                ? "Trợ lý đang bận, vui lòng thử lại sau một phút."
                : "Trợ lý tạm thời không khả dụng. Vui lòng thử lại sau.";
    }

    /** One search's own results, before they are merged with the rest of the round's. */
    private record SearchResult(SearchQuery query, List<ProductCard> cards) {
    }

    /** A call the model has asked for, accumulated across the fragments that describe it. */
    private static final class PendingCall {
        private String id = "";
        private String name = "";
        private String arguments = "";

        @Override
        public String toString() {
            return id + " " + name + " args=" + arguments;
        }
    }

    /** Mutable state for a single turn, shared between the stream callbacks. */
    private static final class TurnState {
        /**
         * Calls requested by the stream now being read, keyed on the event index that
         * identifies each one. Cleared per round: a round answers its own calls only.
         */
        private final Map<Integer, PendingCall> calls = new LinkedHashMap<>();
        /** The merged cards of the whole turn, by product id, so repeats collapse. */
        private final Map<UUID, ProductCard> cards = new LinkedHashMap<>();
        private SearchQuery query;
        private String interactionId;
        private String lastStatus = "";
        private int functionCalls;
        private boolean textEmitted;
        private boolean productsSent;
        private boolean errored;

        private PendingCall call(int index) {
            return calls.computeIfAbsent(index, i -> new PendingCall());
        }

        /**
         * The calls this round actually has to answer. A fragment can create an entry before
         * its {@code step.start} names it; one that never arrived cannot be answered, and a
         * {@code function_result} with no call id would 400 the continuation.
         */
        private List<PendingCall> requestedCalls() {
            return calls.values().stream().filter(c -> !c.id.isBlank()).toList();
        }

        private void startRound() {
            calls.clear();
            // Each round is its own interaction, and the next continuation chains to the one
            // that asked for the results it carries.
            interactionId = null;
        }

        private void mergeCards(List<ProductCard> fresh, SearchQuery searchQuery, int cap) {
            if (query == null) {
                query = searchQuery;
            }
            for (ProductCard card : fresh) {
                if (cards.size() >= cap) {
                    break;
                }
                cards.putIfAbsent(card.id(), card);
            }
        }
    }
}
