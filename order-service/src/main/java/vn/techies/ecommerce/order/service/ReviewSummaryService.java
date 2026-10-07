package vn.techies.ecommerce.order.service;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.techies.ecommerce.order.api.dto.OrderDtos.ReviewSummaryResponse;
import vn.techies.ecommerce.order.client.AiClient;
import vn.techies.ecommerce.order.client.CatalogClient;
import vn.techies.ecommerce.order.domain.ProductReview;
import vn.techies.ecommerce.order.domain.ProductReviewSummary;
import vn.techies.ecommerce.order.repository.ProductReviewRepository;
import vn.techies.ecommerce.order.repository.ProductReviewSummaryRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The product page's review brief: cached here, written by ai-service.
 *
 * <p>Two rules make this affordable and safe. Generation is keyed on the review count, so a
 * product nobody has reviewed since costs nothing however often its page is opened. And every
 * failure degrades to the previous summary, or to {@code null} — the reviews underneath are
 * the real content, and a rate-limited assistant is expected on this project.
 */
@Service
@RequiredArgsConstructor
public class ReviewSummaryService {

    /** A summary of one review is that review again, and two is not a consensus. */
    static final int MIN_REVIEWS = 3;
    /** Matches ai-service's own cap. Newest first, so a long-lived product stays current. */
    static final int MAX_REVIEWS_SENT = 50;

    private static final Logger log = LoggerFactory.getLogger(ReviewSummaryService.class);

    private final ProductReviewRepository reviews;
    private final ProductReviewSummaryRepository summaries;
    private final CatalogClient catalogClient;
    private final AiClient aiClient;

    /** @return the summary, or null when there is nothing worth summarising or nothing cached. */
    @Transactional
    public ReviewSummaryResponse forProduct(UUID productId) {
        long reviewCount = reviews.countByProductId(productId);
        if (reviewCount < MIN_REVIEWS) {
            return null;
        }

        Optional<ProductReviewSummary> cached = summaries.findById(productId);
        if (cached.isPresent() && cached.get().getReviewCount() == reviewCount) {
            return toResponse(cached.get());
        }

        try {
            return toResponse(generate(productId, (int) reviewCount, cached));
        } catch (Exception ex) {
            // Stale beats nothing, and nothing beats an error.
            log.warn("Could not refresh the review summary for {}, serving {}: {}", productId,
                    cached.isPresent() ? "the cached one" : "null", ex.toString());
            return cached.map(ReviewSummaryService::toResponse).orElse(null);
        }
    }

    private ProductReviewSummary generate(UUID productId, int reviewCount,
                                          Optional<ProductReviewSummary> cached) {
        List<AiClient.Review> input = reviews
                .findByProductIdOrderByCreatedAtDesc(productId, PageRequest.of(0, MAX_REVIEWS_SENT))
                .map(review -> new AiClient.Review(review.getRating(), review.getComment()))
                .getContent();

        AiClient.ReviewSummary written = aiClient.summarise(
                new AiClient.ReviewSummaryRequest(productNameOf(productId), input));

        ProductReviewSummary summary = cached.orElse(null);
        if (summary == null) {
            summary = ProductReviewSummary.of(productId, written.pros(), written.cons(),
                    written.verdict(), reviewCount);
        } else {
            summary.regenerate(written.pros(), written.cons(), written.verdict(), reviewCount);
        }

        log.info("Generated a review summary for {} from {} reviews", productId, reviewCount);
        return summaries.save(summary);
    }

    /**
     * The model needs to know what it is summarising, and the name lives in catalog. Only read
     * when generating, so a cache hit stays a single local query.
     */
    private String productNameOf(UUID productId) {
        List<CatalogClient.ProductSnapshot> found =
                catalogClient.batch(new CatalogClient.BatchRequest(List.of(productId)));
        if (found.isEmpty()) {
            throw new IllegalStateException("catalog does not know product " + productId);
        }
        return found.getFirst().name();
    }

    private static ReviewSummaryResponse toResponse(ProductReviewSummary summary) {
        return new ReviewSummaryResponse(summary.getPros(), summary.getCons(),
                summary.getVerdict(), summary.getReviewCount(), summary.getGeneratedAt());
    }
}
