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
 * <p>A turn that calls a tool needs two requests: the first ends with
 * {@code status: requires_action} and a {@code function_call} step; the second supplies the
 * result. The continuation is chained with {@code previous_interaction_id}, which requires
 * {@code store: true} — so Google retains the interaction. We persist nothing ourselves.
 */
@Component
public class GeminiClient {

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

    /** Continuation after a tool ran, chained to the interaction that requested it. */
    public Map<String, Object> toolResultRequest(String previousInteractionId, String callId,
                                                 String toolName, String resultJson) {
        return Map.of(
                "model", properties.model(),
                "stream", true,
                "store", true,
                "previous_interaction_id", previousInteractionId,
                "input", List.of(Map.of(
                        "type", "function_result",
                        "call_id", callId,
                        "name", toolName,
                        "result", List.of(Map.of("type", "text", "text", resultJson)))));
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
                "name", "search_products",
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
}
