package vn.techies.ecommerce.ai.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public final class ChatDtos {

    private ChatDtos() {
    }

    /**
     * @param productId the PDP the popup was opened from. Product facts are fetched here,
     *                  never taken from the client.
     * @param messages  the conversation so far, oldest first. The app holds it and discards
     *                  it when the popup closes; this service stores nothing.
     */
    public record ChatRequest(
            @NotNull UUID productId,
            // @Valid is what makes the per-message @Size cascade. Without it a client
            // can send an arbitrarily long message straight into the prompt.
            @NotNull @Size(min = 1, max = 20) @Valid List<Message> messages) {
    }

    public record Message(
            @NotBlank String role,           // "user" or "assistant"
            @NotBlank @Size(max = 2000) String content) {
    }

    // ---- SSE payloads -----------------------------------------------------------------
    // Event names are part of the contract with the app: token, tool_start, products,
    // done, error.

    /** A fragment of the reply. Append these in order. */
    public record TokenEvent(String text) {
    }

    /** The model paused to search. The app shows "đang tìm sản phẩm…" instead of freezing. */
    public record ToolStartEvent(String tool, String message) {
    }

    /**
     * @param total   how many products actually matched, which may exceed what is shown.
     * @param query   echoed back so the app can deep-link the product list screen.
     * @param products at most {@code techies.gemini.max-products} cards.
     */
    public record ProductsEvent(long total, SearchQuery query, List<ProductCard> products) {
    }

    public record SearchQuery(String keyword, UUID categoryId, BigDecimal minPrice,
                              BigDecimal maxPrice, String sort) {
    }

    public record ProductCard(UUID id, String name, BigDecimal price, String thumbnailUrl) {
    }

    public record DoneEvent(String finishReason) {
    }

    /** Mid-stream failures cannot change the HTTP status, so they arrive as this event. */
    public record ErrorEvent(String code, String message) {
    }
}
