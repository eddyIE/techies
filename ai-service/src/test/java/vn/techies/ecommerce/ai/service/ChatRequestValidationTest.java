package vn.techies.ecommerce.ai.service;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vn.techies.ecommerce.ai.api.dto.ChatDtos.ChatRequest;
import vn.techies.ecommerce.ai.api.dto.ChatDtos.Message;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The chat endpoint costs money per call, so its input bounds are worth pinning. */
class ChatRequestValidationTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    @DisplayName("a normal request validates")
    void acceptsValidRequest() {
        ChatRequest request = new ChatRequest(UUID.randomUUID(),
                List.of(new Message("user", "Sản phẩm này có tốt không?")));

        assertThat(validator.validate(request)).isEmpty();
    }

    @Test
    @DisplayName("an empty conversation is rejected")
    void rejectsEmptyHistory() {
        ChatRequest request = new ChatRequest(UUID.randomUUID(), List.of());

        assertThat(validator.validate(request)).isNotEmpty();
    }

    @Test
    @DisplayName("an oversized conversation is rejected before it reaches the model")
    void rejectsTooManyMessages() {
        List<Message> many = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            many.add(new Message("user", "xin chào"));
        }

        assertThat(validator.validate(new ChatRequest(UUID.randomUUID(), many))).isNotEmpty();
    }

    @Test
    @DisplayName("a single very long message is rejected, capping prompt-stuffing")
    void rejectsOversizedMessage() {
        ChatRequest request = new ChatRequest(UUID.randomUUID(),
                List.of(new Message("user", "x".repeat(2001))));

        assertThat(validator.validate(request)).isNotEmpty();
    }

    @Test
    @DisplayName("a missing product id is rejected — product facts are never client-supplied")
    void rejectsMissingProduct() {
        ChatRequest request = new ChatRequest(null, List.of(new Message("user", "hi")));

        assertThat(validator.validate(request)).isNotEmpty();
    }
}
