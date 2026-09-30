package vn.techies.ecommerce.order.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * A review of a product, written by someone who bought it.
 *
 * <p>Keyed on the ORDER LINE, not the product: buying the same thing twice earns two reviews.
 * One-per-product would leave a repeat order permanently un-reviewable while the app shows it
 * as "not reviewed yet".
 *
 * <p>{@code authorName} is a snapshot. A review should keep displaying the name it was written
 * under, and it lets the seeded demo rows exist without inventing accounts.
 */
@Entity
@Table(name = "product_reviews")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProductReview {

    @Id
    private UUID id;

    /** Null only for seeded demo rows, which belong to no real purchase. */
    @Column(name = "order_item_id", unique = true)
    private UUID orderItemId;

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "author_name", nullable = false, length = 120)
    private String authorName;

    @Column(nullable = false)
    private short rating;

    @Column(length = 1000)
    private String comment;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public static ProductReview write(UUID orderItemId, UUID productId, UUID userId,
                                      String authorName, int rating, String comment) {
        ProductReview r = new ProductReview();
        r.id = UUID.randomUUID();
        r.orderItemId = orderItemId;
        r.productId = productId;
        r.userId = userId;
        r.authorName = authorName;
        r.rating = (short) rating;
        r.comment = comment == null || comment.isBlank() ? null : comment.strip();
        r.createdAt = Instant.now();
        return r;
    }
}
