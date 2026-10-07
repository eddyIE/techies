package vn.techies.ecommerce.order.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One product's cached review brief.
 *
 * <p>{@code reviewCount} is what makes this affordable: a product whose count has not changed
 * costs nothing however often its page is opened, so generation is bounded by review volume
 * rather than by traffic.
 */
@Entity
@Table(name = "product_review_summaries")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProductReviewSummary {

    @Id
    @Column(name = "product_id")
    private UUID productId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private List<String> pros;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private List<String> cons;

    @Column(nullable = false, length = 500)
    private String verdict;

    @Column(name = "review_count", nullable = false)
    private int reviewCount;

    @Column(name = "generated_at", nullable = false)
    private Instant generatedAt;

    public static ProductReviewSummary of(UUID productId, List<String> pros, List<String> cons,
                                          String verdict, int reviewCount) {
        ProductReviewSummary summary = new ProductReviewSummary();
        summary.productId = productId;
        summary.pros = pros == null ? List.of() : pros;
        summary.cons = cons == null ? List.of() : cons;
        summary.verdict = verdict;
        summary.reviewCount = reviewCount;
        summary.generatedAt = Instant.now();
        return summary;
    }

    /** Replaces the text in place, so one product keeps one row however often it regenerates. */
    public void regenerate(List<String> pros, List<String> cons, String verdict, int reviewCount) {
        this.pros = pros == null ? List.of() : pros;
        this.cons = cons == null ? List.of() : cons;
        this.verdict = verdict;
        this.reviewCount = reviewCount;
        this.generatedAt = Instant.now();
    }
}
