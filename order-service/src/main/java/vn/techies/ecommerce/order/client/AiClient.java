package vn.techies.ecommerce.order.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;

/**
 * The one arrow from `order` to `ai`, and it is best-effort: a product page never fails because
 * the assistant is out of quota (SPEC-order.md, AI review summary).
 */
@FeignClient(name = "ai-service", path = "/ai")
public interface AiClient {

    @PostMapping("/review-summary")
    ReviewSummary summarise(@RequestBody ReviewSummaryRequest request);

    record ReviewSummaryRequest(String productName, List<Review> reviews) {
    }

    record Review(int rating, String comment) {
    }

    record ReviewSummary(List<String> pros, List<String> cons, String verdict) {
    }
}
