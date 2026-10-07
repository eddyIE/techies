package vn.techies.ecommerce.ai.api;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.techies.ecommerce.ai.api.dto.ReviewSummaryDtos.ReviewSummaryRequest;
import vn.techies.ecommerce.ai.api.dto.ReviewSummaryDtos.ReviewSummaryResponse;
import vn.techies.ecommerce.ai.config.GeminiProperties;
import vn.techies.ecommerce.ai.service.ReviewSummaryService;
import vn.techies.ecommerce.common.error.ApiException;
import vn.techies.ecommerce.common.error.ErrorCode;

/**
 * Internal only: the gateway does not route this. order-service is the sole caller, and no
 * customer should be able to spend the day's quota by reloading a product page.
 *
 * <p>Not streamed, unlike chat. A summary is generated once, cached by the caller and then read
 * many times, so there is nothing to watch filling and SSE would complicate both ends.
 */
@RestController
@RequestMapping("/ai")
@RequiredArgsConstructor
class ReviewSummaryController {

    private final ReviewSummaryService reviewSummaryService;
    private final GeminiProperties properties;

    @PostMapping("/review-summary")
    ReviewSummaryResponse summarise(@Valid @RequestBody ReviewSummaryRequest request) {
        if (!properties.isConfigured()) {
            throw new ApiException(ErrorCode.SERVICE_UNAVAILABLE, "Trợ lý chưa được cấu hình");
        }
        return reviewSummaryService.summarise(request);
    }
}
