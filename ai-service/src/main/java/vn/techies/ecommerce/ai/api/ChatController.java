package vn.techies.ecommerce.ai.api;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import vn.techies.ecommerce.ai.api.dto.ChatDtos.ChatRequest;
import vn.techies.ecommerce.ai.api.dto.ChatDtos.DoneEvent;
import vn.techies.ecommerce.ai.api.dto.ChatDtos.ErrorEvent;
import vn.techies.ecommerce.ai.api.dto.ChatDtos.ProductsEvent;
import vn.techies.ecommerce.ai.api.dto.ChatDtos.TokenEvent;
import vn.techies.ecommerce.ai.api.dto.ChatDtos.ToolStartEvent;
import vn.techies.ecommerce.ai.config.GeminiProperties;
import vn.techies.ecommerce.ai.service.ChatService;
import vn.techies.ecommerce.common.security.CurrentUser;
import vn.techies.ecommerce.common.security.UserPrincipal;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Streams one assistant reply as Server-Sent Events.
 *
 * <p>Event names are the contract with the app:
 * <ul>
 *   <li>{@code token} — a fragment of the reply, appended in order</li>
 *   <li>{@code tool_start} — the model paused to search; show a searching indicator</li>
 *   <li>{@code products} — product cards plus the true total and the query to deep-link</li>
 *   <li>{@code done} — the turn finished</li>
 *   <li>{@code error} — a failure that happened after the stream opened</li>
 * </ul>
 *
 * <p>Once the response has begun the HTTP status can no longer change, so a failure
 * mid-stream arrives as an {@code error} event carrying the same {@code code}/{@code message}
 * shape as the project's normal error envelope. Failures before streaming starts still throw
 * and produce the standard envelope with a real status code.
 */
@RestController
@RequestMapping("/ai")
@RequiredArgsConstructor
class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private final ChatService chatService;
    private final GeminiProperties properties;

    /**
     * One thread per in-flight chat. A virtual-thread executor keeps this cheap: each turn
     * spends nearly all its time blocked on Gemini, not on CPU.
     */
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter chat(@CurrentUser UserPrincipal principal, @Valid @RequestBody ChatRequest request) {
        SseEmitter emitter = new SseEmitter(properties.timeoutSeconds() * 1000L);

        if (!properties.isConfigured()) {
            // Deliberately not a startup failure: the rest of the service should run without
            // a key, and the app simply finds the assistant unavailable.
            send(emitter, "error", new ErrorEvent("SERVICE_UNAVAILABLE",
                    "Trợ lý chưa được cấu hình."));
            emitter.complete();
            return emitter;
        }

        executor.execute(() -> {
            try {
                chatService.chat(request, new ChatService.ChatListener() {
                    @Override
                    public void onToken(String text) {
                        send(emitter, "token", new TokenEvent(text));
                    }

                    @Override
                    public void onToolStart(String tool) {
                        send(emitter, "tool_start", new ToolStartEvent(tool, "Đang tìm sản phẩm…"));
                    }

                    @Override
                    public void onProducts(ProductsEvent products) {
                        send(emitter, "products", products);
                    }

                    @Override
                    public void onDone(String finishReason) {
                        send(emitter, "done", new DoneEvent(finishReason));
                        emitter.complete();
                    }

                    @Override
                    public void onError(String code, String message) {
                        send(emitter, "error", new ErrorEvent(code, message));
                        emitter.complete();
                    }
                });
            } catch (Exception ex) {
                log.error("Unhandled failure while streaming chat for user {}", principal.userId(), ex);
                send(emitter, "error", new ErrorEvent("INTERNAL_ERROR",
                        "Trợ lý tạm thời không khả dụng."));
                emitter.complete();
            }
        });

        return emitter;
    }

    private void send(SseEmitter emitter, String name, Object payload) {
        try {
            emitter.send(SseEmitter.event().name(name).data(payload, MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException ex) {
            // The client closed the popup mid-reply. Normal, not an error worth alarming on.
            log.debug("SSE client disconnected: {}", ex.toString());
        }
    }
}
