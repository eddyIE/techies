package vn.techies.ecommerce.ai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import vn.techies.ecommerce.ai.api.dto.ChatDtos.ChatRequest;
import vn.techies.ecommerce.ai.api.dto.ChatDtos.Message;
import vn.techies.ecommerce.ai.api.dto.ChatDtos.ProductsEvent;
import vn.techies.ecommerce.ai.client.CatalogClient;
import vn.techies.ecommerce.ai.client.GeminiClient;
import vn.techies.ecommerce.ai.client.InventoryClient;
import vn.techies.ecommerce.ai.config.GeminiProperties;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The tool-calling loop, driven by canned Gemini streams.
 *
 * <p>A comparison ("so sánh X với Y") needs two catalogue lookups, and both ways of asking for
 * them used to end the turn with cards and not one word of text: parallel calls concatenated
 * their argument fragments into malformed JSON, and a sequential second call was logged and
 * dropped, leaving the interaction at {@code requires_action} forever.
 *
 * <p>{@link GeminiClient} is spied rather than mocked, so the request bodies asserted on here
 * are the ones it really builds.
 */
class ChatServiceToolLoopTest {

    private static final UUID PRODUCT = UUID.randomUUID();
    private static final UUID TV_A = UUID.randomUUID();
    private static final UUID TV_B = UUID.randomUUID();
    private static final UUID TV_C = UUID.randomUUID();
    private static final UUID TV_D = UUID.randomUUID();

    private final ObjectMapper mapper = new ObjectMapper();
    private final GeminiProperties properties =
            new GeminiProperties("key", "http://localhost", "model", 60, 10, 3, false);

    private final GeminiClient gemini = spy(new GeminiClient(properties, new ObjectMapper()));
    private final CatalogClient catalog = mock(CatalogClient.class);
    private final InventoryClient inventory = mock(InventoryClient.class);

    private ChatService service;

    /** Every request body handed to {@link GeminiClient#stream}, in order. */
    private final List<Map<String, Object>> sent = new ArrayList<>();
    private final Recorder listener = new Recorder();

    @BeforeEach
    void setUp() {
        service = new ChatService(gemini, catalog, inventory,
                new SystemPromptBuilder(properties), properties, mapper);

        when(catalog.getProduct(any())).thenReturn(new CatalogClient.ProductDetail(
                PRODUCT, "Samsung Neo QLED 55", "neo-qled-55", "TV 4K.",
                new BigDecimal("25990000.00"), "thumb", UUID.randomUUID(), "Tivi", List.of()));
        when(catalog.categories()).thenReturn(List.of(
                new CatalogClient.Category(UUID.randomUUID(), "Tivi", "tivi", "img", 1)));
        when(inventory.getStock(any()))
                .thenReturn(new InventoryClient.StockResponse(PRODUCT, 7, true));
        // Anything not explicitly stubbed finds nothing, rather than returning null.
        when(catalog.search(any(), any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(page());
    }

    // ---- failure mode B: parallel calls --------------------------------------------------

    @Test
    @DisplayName("two parallel calls run two searches, each with its own arguments")
    void parallelCallsBecomeTwoSearches() {
        script(parallelComparison(), List.of(text("Dạ, hai mẫu này ạ."), completed("completed")));

        chat();

        // Appended to one buffer these two became {"keyword":"Neo QLED"}{"keyword":"OLED"},
        // of which Jackson reads the first object and drops the rest: one search, not two.
        ArgumentCaptor<String> keywords = ArgumentCaptor.forClass(String.class);
        verify(catalog, times(2))
                .search(keywords.capture(), any(), any(), any(), any(), anyInt(), anyInt());
        assertThat(keywords.getAllValues()).containsExactly("Neo QLED", "OLED");
    }

    @Test
    @DisplayName("both calls are answered in a single continuation request")
    void bothResultsGoBackTogether() {
        script(parallelComparison(), List.of(text("ok"), completed("completed")));

        chat();

        assertThat(functionResults(sent.get(1)))
                .extracting(r -> r.get("call_id"))
                .containsExactly("call_340910", "call_340913");
    }

    @Test
    @DisplayName("the searches of one turn arrive as exactly one products event")
    void searchesShareOneProductsEvent() {
        stubSearch("Neo QLED", card(TV_A, "Neo QLED 55"));
        stubSearch("OLED", card(TV_B, "LG OLED C4"));
        script(parallelComparison(), List.of(text("ok"), completed("completed")));

        chat();

        assertThat(listener.products).hasSize(1);
    }

    @Test
    @DisplayName("merged cards carry both searches, not just the first")
    void mergedCardsCoverEverySearch() {
        stubSearch("Neo QLED", card(TV_A, "Neo QLED 55"));
        stubSearch("OLED", card(TV_B, "LG OLED C4"));
        script(parallelComparison(), List.of(text("ok"), completed("completed")));

        chat();

        assertThat(listener.products.getFirst().products())
                .extracting(p -> p.id())
                .containsExactlyInAnyOrder(TV_A, TV_B);
    }

    @Test
    @DisplayName("a product matched by both searches is sent once")
    void mergedCardsAreDedupedById() {
        stubSearch("Neo QLED", card(TV_A, "Neo QLED 55"), card(TV_B, "LG OLED C4"));
        stubSearch("OLED", card(TV_B, "LG OLED C4"), card(TV_C, "Sony Bravia"));
        script(parallelComparison(), List.of(text("ok"), completed("completed")));

        chat();

        assertThat(listener.products.getFirst().products())
                .extracting(p -> p.id())
                .containsExactlyInAnyOrder(TV_A, TV_B, TV_C);
    }

    @Test
    @DisplayName("merged cards are capped at max-products")
    void mergedCardsAreCapped() {
        stubSearch("Neo QLED", card(TV_A, "A"), card(TV_B, "B"));
        stubSearch("OLED", card(TV_C, "C"), card(TV_D, "D"));
        script(parallelComparison(), List.of(text("ok"), completed("completed")));

        chat();

        assertThat(listener.products.getFirst().products()).hasSize(properties.maxProducts());
    }

    @Test
    @DisplayName("total still equals the number of cards sent, never more")
    void totalEqualsCardsSent() {
        stubSearch("Neo QLED", card(TV_A, "A"), card(TV_B, "B"));
        stubSearch("OLED", card(TV_C, "C"), card(TV_D, "D"));
        script(parallelComparison(), List.of(text("ok"), completed("completed")));

        chat();

        ProductsEvent event = listener.products.getFirst();
        assertThat(event.total()).isEqualTo(event.products().size());
    }

    @Test
    @DisplayName("one tool_start per turn, whatever the model asks for")
    void oneToolStartPerTurn() {
        script(parallelComparison(),
                List.of(created("int-2"), callStart(0, "call_3"), argsDelta(0, "{\"keyword\":\"QLED\"}"),
                        completed("requires_action")),
                List.of(text("ok"), completed("completed")));

        chat();

        assertThat(listener.toolStarts).hasSize(1);
    }

    @Test
    @DisplayName("the cards never precede the reply they belong to")
    void cardsFollowTheFirstToken() {
        stubSearch("Neo QLED", card(TV_A, "A"));
        script(parallelComparison(), List.of(text("Dạ, "), text("hai mẫu ạ."), completed("completed")));

        chat();

        assertThat(listener.order).startsWith("tool_start", "token", "products");
    }

    // ---- failure mode A: a second, sequential call ----------------------------------------

    @Test
    @DisplayName("a second round of tool calls is answered rather than dropped")
    void sequentialCallIsAnswered() {
        script(List.of(created("int-1"), callStart(0, "call_1"),
                        argsDelta(0, "{\"keyword\":\"Neo QLED\"}"), completed("requires_action")),
                List.of(created("int-2"), callStart(0, "call_2"),
                        argsDelta(0, "{\"keyword\":\"OLED\"}"), completed("requires_action")),
                List.of(text("Dạ, so sánh hai mẫu ạ."), completed("completed")));

        chat();

        verify(catalog).search(eq("OLED"), any(), any(), any(), any(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("a two-round comparison still produces the model's own text")
    void sequentialCallStillAnswersInWords() {
        script(List.of(created("int-1"), callStart(0, "call_1"),
                        argsDelta(0, "{\"keyword\":\"Neo QLED\"}"), completed("requires_action")),
                List.of(created("int-2"), callStart(0, "call_2"),
                        argsDelta(0, "{\"keyword\":\"OLED\"}"), completed("requires_action")),
                List.of(text("Dạ, so sánh hai mẫu ạ."), completed("completed")));

        chat();

        assertThat(listener.text()).isEqualTo("Dạ, so sánh hai mẫu ạ.");
    }

    @Test
    @DisplayName("the second round chains to the interaction that asked for it")
    void eachRoundChainsToItsOwnInteraction() {
        script(List.of(created("int-1"), callStart(0, "call_1"),
                        argsDelta(0, "{\"keyword\":\"Neo QLED\"}"), completed("requires_action")),
                List.of(created("int-2"), callStart(0, "call_2"),
                        argsDelta(0, "{\"keyword\":\"OLED\"}"), completed("requires_action")),
                List.of(text("ok"), completed("completed")));

        chat();

        assertThat(sent.get(2).get("previous_interaction_id")).isEqualTo("int-2");
    }

    // ---- the round cap ---------------------------------------------------------------------

    @Test
    @DisplayName("a model that keeps asking is cut off after three tool rounds")
    void toolRoundsAreCapped() {
        scriptEndlessToolCalls();

        chat();

        // Three rounds of results, plus the final request that declares no tools.
        verify(gemini, times(4)).stream(any(), any());
    }

    @Test
    @DisplayName("the request after the cap offers no tools, so the model has to answer")
    void theCappedRequestDeclaresNoTools() {
        scriptEndlessToolCalls();

        chat();

        assertThat(sent.get(3)).doesNotContainKey("tools");
    }

    @Test
    @DisplayName("no search runs past the cap")
    void noSearchRunsPastTheCap() {
        scriptEndlessToolCalls();

        chat();

        verify(catalog, times(3)).search(any(), any(), any(), any(), any(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("a capped turn still finishes with done")
    void aCappedTurnStillCompletes() {
        scriptEndlessToolCalls();

        chat();

        assertThat(listener.order).endsWith("done");
    }

    // ---- the no-text guarantee -------------------------------------------------------------

    @Test
    @DisplayName("done never follows zero tokens: a silent turn gets a fallback")
    void silentTurnStillSaysSomething() {
        script(List.of(created("int-1"), completed("completed")));

        chat();

        assertThat(listener.text()).isEqualTo("Dạ, em chưa trả lời được câu này. "
                + "Anh/chị hỏi lại giúp em ạ.");
    }

    @Test
    @DisplayName("the fallback points at the cards when cards were found")
    void theFallbackMentionsTheCards() {
        stubSearch("Neo QLED", card(TV_A, "Neo QLED 55"));
        script(parallelComparison(), List.of(completed("completed")));

        chat();

        assertThat(listener.text()).isEqualTo("Dạ, em gửi anh/chị vài mẫu phù hợp ạ.");
    }

    @Test
    @DisplayName("the fallback arrives before done, not after it")
    void theFallbackPrecedesDone() {
        script(List.of(created("int-1"), completed("completed")));

        chat();

        assertThat(listener.order).containsExactly("token", "done");
    }

    @Test
    @DisplayName("a model that spoke gets no fallback appended")
    void noFallbackWhenTheModelSpoke() {
        script(List.of(created("int-1"), text("Dạ, máy này còn hàng ạ."), completed("completed")));

        chat();

        assertThat(listener.text()).isEqualTo("Dạ, máy này còn hàng ạ.");
    }

    // ---- harness ---------------------------------------------------------------------------

    private void chat() {
        service.chat(new ChatRequest(PRODUCT, List.of(new Message("user", "so sánh hai mẫu tivi"))),
                listener);
    }

    /** The real shape of a parallel comparison: two calls, their fragments interleaved. */
    private static List<Map<String, Object>> parallelComparison() {
        return List.of(
                created("int-1"),
                callStart(1, "call_340910"),
                callStart(2, "call_340913"),
                argsDelta(1, "{\"keyword\":\"Neo"),
                argsDelta(2, "{\"keyword\":\"O"),
                argsDelta(1, " QLED\"}"),
                argsDelta(2, "LED\"}"),
                completed("requires_action"));
    }

    /** Feeds one scripted stream per call to {@code stream}, in order. */
    @SafeVarargs
    private void script(List<Map<String, Object>>... rounds) {
        AtomicInteger round = new AtomicInteger();
        answerWith(i -> i < rounds.length ? rounds[i] : List.of(), round);
    }

    /** A model that asks for a search every single time it is given the chance. */
    private void scriptEndlessToolCalls() {
        answerWith(i -> List.of(created("int-" + i), callStart(0, "call_" + i),
                argsDelta(0, "{\"keyword\":\"QLED\"}"), completed("requires_action")),
                new AtomicInteger());
    }

    private void answerWith(java.util.function.IntFunction<List<Map<String, Object>>> rounds,
                            AtomicInteger round) {
        doAnswer(invocation -> {
            sent.add(invocation.getArgument(0));
            Consumer<JsonNode> onEvent = invocation.getArgument(1);
            for (Map<String, Object> event : rounds.apply(round.getAndIncrement())) {
                onEvent.accept(mapper.valueToTree(event));
            }
            return null;
        }).when(gemini).stream(any(), any());
    }

    private void stubSearch(String keyword, CatalogClient.ProductSummary... products) {
        when(catalog.search(eq(keyword), any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(page(products));
    }

    private static CatalogClient.ProductPage page(CatalogClient.ProductSummary... products) {
        List<CatalogClient.ProductSummary> content = Arrays.asList(products);
        return new CatalogClient.ProductPage(content, 0, 5, content.size(), 1);
    }

    private static CatalogClient.ProductSummary card(UUID id, String name) {
        return new CatalogClient.ProductSummary(id, name, "slug", new BigDecimal("19990000.00"),
                "thumb", UUID.randomUUID(), "Tivi");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> functionResults(Map<String, Object> body) {
        return (List<Map<String, Object>>) body.get("input");
    }

    // ---- event builders, in the live API's vocabulary --------------------------------------

    private static Map<String, Object> created(String id) {
        return event("interaction.created", 0, "interaction", Map.of("id", id));
    }

    private static Map<String, Object> completed(String status) {
        return event("interaction.completed", 0, "interaction",
                Map.of("id", "int-1", "status", status));
    }

    private static Map<String, Object> callStart(int index, String callId) {
        return event("step.start", index, "step", Map.of(
                "type", "function_call", "id", callId, "name", "search_products",
                "arguments", Map.of()));
    }

    private static Map<String, Object> argsDelta(int index, String fragment) {
        return event("step.delta", index, "delta",
                Map.of("type", "arguments_delta", "arguments", fragment));
    }

    private static Map<String, Object> text(String text) {
        return event("step.delta", 0, "delta", Map.of("type", "text", "text", text));
    }

    private static Map<String, Object> event(String type, int index, String key, Object value) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("event_type", type);
        event.put("index", index);
        event.put(key, value);
        return event;
    }

    /** Collects the SSE events a turn produces, and the order they came in. */
    private static final class Recorder implements ChatService.ChatListener {
        private final List<String> tokens = new ArrayList<>();
        private final List<String> toolStarts = new ArrayList<>();
        private final List<ProductsEvent> products = new ArrayList<>();
        private final List<String> order = new ArrayList<>();

        private String text() {
            return String.join("", tokens);
        }

        @Override
        public void onToken(String text) {
            tokens.add(text);
            order.add("token");
        }

        @Override
        public void onToolStart(String tool) {
            toolStarts.add(tool);
            order.add("tool_start");
        }

        @Override
        public void onProducts(ProductsEvent event) {
            products.add(event);
            order.add("products");
        }

        @Override
        public void onDone(String finishReason) {
            order.add("done");
        }

        @Override
        public void onError(String code, String message) {
            order.add("error:" + code);
        }
    }
}
