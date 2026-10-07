package vn.techies.ecommerce.ai.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

public final class ReviewSummaryDtos {

    private ReviewSummaryDtos() {
    }

    /**
     * The reviews arrive in the body rather than being fetched. order-service owns them, and
     * keeping it that way leaves this service a read-only leaf with no schema, and makes the
     * summary a pure function of its input.
     */
    public record ReviewSummaryRequest(
            @NotBlank @Size(max = 200) String productName,
            @NotEmpty @Size(max = 50) @Valid List<ReviewInput> reviews) {
    }

    public record ReviewInput(
            @Min(1) @Max(5) int rating,
            @Size(max = 2000) String comment) {
    }

    /** Short phrases for the app to render as chips, not prose it would have to lay out. */
    public record ReviewSummaryResponse(List<String> pros, List<String> cons, String verdict) {
    }
}
