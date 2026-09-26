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
 * <p>A turn that triggers a search takes two Gemini calls: the model asks for
 * {@code search_products} and stops, this service runs the real search against
 * catalog-service, then the model writes its answer from the results. The model never
 * reaches the catalogue itself.
 *
 * <p>Products are emitted as their own event rather than being formatted by the model. The
 * app renders real cards that navigate to the PDP, and the model cannot invent a price that
 * disagrees with the catalogue.
 */
@Service
@RequiredArgsConstructor
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

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
        TurnState pending = new TurnState();

        gemini.stream(
                gemini.firstRequest(systemPrompt, input, List.of(
                        GeminiClient.searchProductsTool(
                                categories.stream().map(CatalogClient.Category::name).toList()))),
                event -> handleEvent(event, listener, pending, node -> {
                    pending.toolRequested = true;
                    pending.callId = node.path("id").asText();
                    pending.toolName = node.path("name").asText();
                    // arguments are NOT taken from here: step.start carries an empty object
                    // and the real values arrive as arguments_delta fragments.
                }));

        if (pending.errored || !pending.toolRequested) {
            if (!pending.errored) {
                listener.onDone("stop");
            }
            return;
        }

        // ---- the model asked to search -------------------------------------------------
        listener.onToolStart(pending.toolName);

        String resultJson;
        try {
            resultJson = runSearch(pending.arguments, categories, listener);
        } catch (Exception ex) {
            log.warn("Product search failed mid-turn: {}", ex.toString());
            listener.onError("SERVICE_UNAVAILABLE", "Không thể tìm sản phẩm lúc này.");
            return;
        }

        if (pending.interactionId == null || pending.interactionId.isBlank()) {
            // Without the interaction id the continuation cannot be chained.
            listener.onError("SERVICE_UNAVAILABLE", "Trợ lý tạm thời không khả dụng.");
            return;
        }

        gemini.stream(
                gemini.toolResultRequest(pending.interactionId, pending.callId, pending.toolName, resultJson),
                event -> handleEvent(event, listener, pending, node -> { }));

        if (!pending.errored) {
            listener.onDone("stop");
        }
    }

    /** Maps one Gemini stream event onto the listener. */
    private void handleEvent(JsonNode event, ChatListener listener, TurnState state,
                             java.util.function.Consumer<JsonNode> onFunctionCall) {
        String type = event.path("event_type").asText();

        switch (type) {
            case "interaction.created", "interaction.completed" -> {
                String id = event.path("interaction").path("id").asText(null);
                if (id != null && !id.isBlank() && state.interactionId == null) {
                    state.interactionId = id;
                }
            }
            case "step.delta" -> {
                JsonNode delta = event.path("delta");
                String deltaType = delta.path("type").asText();
                if ("text".equals(deltaType)) {
                    String text = delta.path("text").asText("");
                    if (!text.isEmpty()) {
                        listener.onToken(text);
                    }
                } else if ("arguments_delta".equals(deltaType)) {
                    // Tool arguments stream in fragments, exactly like reply text. step.start
                    // announces the call with an EMPTY arguments object, so reading only that
                    // yields a search with no criteria.
                    state.arguments += delta.path("arguments").asText("");
                }
                // thought_signature is internal bookkeeping; ignored.
            }
            case "step.start" -> {
                JsonNode step = event.path("step");
                if ("function_call".equals(step.path("type").asText())) {
                    onFunctionCall.accept(step);
                }
            }
            case "error" -> {
                String message = event.path("error").path("message").asText("");
                log.warn("Gemini stream error: {}", message);
                state.errored = true;
                listener.onError(mapErrorCode(message), userFacingError(message));
            }
            default -> {
                // Unknown event types are ignored on purpose: the API adds them over time and
                // an unrecognised event is not a reason to fail a turn.
            }
        }
    }

    /** Runs the real catalogue search and emits the cards. */
    private String runSearch(String argumentsJson, List<CatalogClient.Category> categories,
                             ChatListener listener) throws Exception {
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

        SearchQuery query = new SearchQuery(keyword, categoryId, minPrice, maxPrice, sort);
        listener.onProducts(new ProductsEvent(page.totalElements(), query, cards));

        // The model sees the total as well as the shown items, so it can say "found 12, here
        // are 3" instead of implying three is everything.
        Map<String, Object> forModel = new LinkedHashMap<>();
        forModel.put("total", page.totalElements());
        forModel.put("shown", cards.size());
        List<Map<String, Object>> items = new ArrayList<>();
        for (ProductCard c : cards) {
            items.add(Map.of("name", c.name(), "price", c.price()));
        }
        forModel.put("products", items);
        return json.writeValueAsString(forModel);
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

    /** Mutable state for a single turn, shared between the stream callbacks. */
    private static final class TurnState {
        private String interactionId;
        private String callId;
        private String toolName;
        private String arguments = "";
        private boolean toolRequested;
        private boolean errored;
    }
}
