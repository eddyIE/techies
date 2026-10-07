package vn.techies.ecommerce.ai.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import vn.techies.ecommerce.ai.config.GeminiProperties;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Talks to the Gemini Interactions API.
 *
 * <p>The request and event shapes here were established by probing the live API rather than
 * from memory — the older {@code generateContent} / {@code contents[].parts[]} format does
 * not apply to this endpoint. Observed vocabulary:
 *
 * <pre>
 *   interaction.created        stream opened
 *   step.start   {step:{type}} "thought" | "model_output" | "function_call"
 *   step.delta   {delta:{type:"text", text}}          incremental reply text
 *                {delta:{type:"thought_signature"}}   internal, ignored
 *   step.stop
 *   interaction.completed      carries usage
 *   error        {error:{message, code}}              mid-stream failure
 * </pre>
 *
 * <p>A turn that calls a tool needs at least two requests: the first ends with
 * {@code status: requires_action} and one or more {@code function_call} steps; the second
 * supplies their results and may itself ask for more. The continuation is chained with
 * {@code previous_interaction_id}, which requires {@code store: true} — so Google retains the
 * interaction. We persist nothing ourselves.
 *
 * <p>Every event carries a top-level {@code index}, and that index is the only thing that
 * separates concurrently requested calls from one another.
 */
@Component
public class GeminiClient {

    /** The only function this service executes itself. */
    public static final String SEARCH_PRODUCTS = "search_products";

    private static final Logger log = LoggerFactory.getLogger(GeminiClient.class);

    private final WebClient webClient;
    private final GeminiProperties properties;
    private final ObjectMapper json;

    public GeminiClient(GeminiProperties properties, ObjectMapper json) {
        this.properties = properties;
        this.json = json;
        this.webClient = WebClient.builder()
                .baseUrl(properties.baseUrl())
                // Gemini streams a full reply in one connection; the default 256KB buffer is
                // ample per event but the codec limit applies to the whole body.
                .codecs(c -> c.defaultCodecs().maxInMemorySize(2 * 1024 * 1024))
                .build();
    }

    /** First call of a turn: system prompt, history and the tool declaration. */
    public Map<String, Object> firstRequest(String systemInstruction, List<Map<String, Object>> input,
                                            List<Map<String, Object>> tools) {
        return Map.of(
                "model", properties.model(),
                "stream", true,
                // Required so the tool continuation can chain with previous_interaction_id.
                "store", true,
                "system_instruction", systemInstruction,
                "input", input,
                "tools", tools);
    }

    /** One executed tool call, ready to be handed back to the model. */
    public record ToolResult(String callId, String toolName, String resultJson) {
    }

    /**
     * Continuation after one or more tools ran, chained to the interaction that requested them.
     *
     * <p>Every result the interaction asked for goes in a single request. A stream can request
     * several calls at once — a comparison asks for two searches — and the interaction stays
     * at {@code status: requires_action} until each one has been answered, so returning only
     * the first leaves the model waiting and it never writes a word.
     *
     * <p>The system instruction and the tools are both declared again. Chaining on
     * {@code previous_interaction_id} alone was not enough: the reply written after a search
     * is a fresh generation, and without the rules restated it re-listed every product,
     * invented features for them and dropped the pinned pronouns — precisely the turn where
     * those rules matter most.
     *
     * <p>An empty {@code tools} list omits the declaration altogether, which is how the caller
     * stops a model that keeps asking for searches: with nothing to call it has to answer.
     */
    public Map<String, Object> toolResultRequest(String previousInteractionId,
                                                 List<ToolResult> results,
                                                 String systemInstruction,
                                                 List<Map<String, Object>> tools) {
        List<Map<String, Object>> input = new ArrayList<>();
        for (ToolResult result : results) {
            input.add(Map.of(
                    "type", "function_result",
                    "call_id", result.callId(),
                    "name", result.toolName(),
                    "result", List.of(Map.of("type", "text", "text", result.resultJson()))));
        }

        // A LinkedHashMap rather than Map.of because `tools` is conditional.
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", properties.model());
        body.put("stream", true);
        body.put("store", true);
        body.put("previous_interaction_id", previousInteractionId);
        body.put("system_instruction", systemInstruction);
        body.put("input", input);
        if (!tools.isEmpty()) {
            body.put("tools", tools);
        }
        return body;
    }

    /**
     * One-shot text request: no tools, no chaining, and nothing stored.
     *
     * <p>Still streamed, because that is the transport this client is built and proven against.
     * The caller just concatenates the pieces instead of forwarding them.
     *
     * <p>{@code store} is false: nothing chains to this interaction, so keeping it server side
     * would only leave state behind.
     */
    public Map<String, Object> textRequest(String systemInstruction, String userText) {
        return Map.of(
                "model", properties.model(),
                "stream", true,
                "store", false,
                "system_instruction", systemInstruction,
                "input", List.of(Map.of("type", "text", "text", userText)));
    }

    /** Runs a request and returns the whole reply as one string. Blocks, like {@link #stream}. */
    public String completeText(Map<String, Object> body) {
        StringBuilder text = new StringBuilder();
        stream(body, event -> {
            if ("step.delta".equals(event.path("event_type").asText())
                    && "text".equals(event.path("delta").path("type").asText())) {
                text.append(event.path("delta").path("text").asText(""));
            }
        });
        return text.toString();
    }

    /**
     * Streams one request, invoking {@code onEvent} for each parsed SSE payload.
     * Blocks until the stream completes, so callers run it on their own thread.
     */
    public void stream(Map<String, Object> body, Consumer<JsonNode> onEvent) {
        Flux<String> lines = webClient.post()
                .uri(uriBuilder -> uriBuilder.queryParam("alt", "sse").build())
                .header("x-goog-api-key", properties.apiKey())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToFlux(String.class);

        lines.toStream().forEach(chunk -> {
            for (String line : chunk.split("\n")) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("event:")) {
                    continue;
                }
                String payload = trimmed.startsWith("data:") ? trimmed.substring(5).trim() : trimmed;
                if (!payload.startsWith("{")) {
                    continue;
                }
                try {
                    onEvent.accept(json.readTree(payload));
                } catch (Exception ex) {
                    // A split chunk can yield a partial line; skipping it is correct, because
                    // the next chunk carries the rest and the stream stays usable.
                    log.debug("Skipped an unparseable stream fragment: {}", ex.toString());
                }
            }
        });
    }

    /**
     * The one tool the assistant may call, described in Vietnamese for a Vietnamese model.
     *
     * <p>{@code category} is an enum of the real category names. Keyword search covers only a
     * product's own name and description, so searching "tai nghe" — a category name that
     * appears in no product's name — returns nothing. Offering the categories explicitly is
     * what makes "find me headphones" work at all.
     */
    public static Map<String, Object> searchProductsTool(List<String> categoryNames) {
        return Map.of(
                "type", "function",
                "name", SEARCH_PRODUCTS,
                "description",
                "Tìm sản phẩm khác trong cửa hàng Techies. Dùng khi khách hỏi về sản phẩm khác, "
                        + "muốn so sánh, tìm theo giá, theo danh mục hoặc xin gợi ý.",
                "parameters", Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "keyword", Map.of("type", "string",
                                        "description", "Từ khóa tìm kiếm, ví dụ 'tai nghe', 'laptop'"),
                                "minPrice", Map.of("type", "number", "description", "Giá tối thiểu (VND)"),
                                "maxPrice", Map.of("type", "number", "description", "Giá tối đa (VND)"),
                                "category", Map.of("type", "string",
                                        "enum", categoryNames,
                                        "description",
                                        "Danh mục sản phẩm. DÙNG trường này khi khách hỏi theo "
                                                + "loại sản phẩm (ví dụ tai nghe, laptop), vì tìm "
                                                + "theo từ khóa chỉ khớp tên sản phẩm."),
                                "sort", Map.of("type", "string",
                                        "enum", List.of("NEWEST", "PRICE_ASC", "PRICE_DESC", "NAME_ASC"),
                                        "description", "Sắp xếp. PRICE_ASC khi khách muốn rẻ nhất.")),
                        "required", List.of()));
    }

    /**
     * Google Search, run by the provider rather than by us.
     *
     * <p>Unlike {@code search_products} this never comes back as a {@code function_call} for
     * this service to execute: Google performs the search inside the same turn and the reply
     * simply arrives grounded. So it costs no extra round trip and needs no handling in the
     * event loop.
     *
     * <p>It exists because the seeded product descriptions are one line long, and customers
     * ask about battery capacity, RAM and screen size. The alternative to grounding is
     * refusing every such question.
     */
    public static Map<String, Object> webSearchTool() {
        return Map.of("type", "google_search");
    }
}
