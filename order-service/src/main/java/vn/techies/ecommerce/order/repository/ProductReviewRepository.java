package vn.techies.ecommerce.order.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vn.techies.ecommerce.order.domain.ProductReview;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface ProductReviewRepository extends JpaRepository<ProductReview, UUID> {

    Page<ProductReview> findByProductIdOrderByCreatedAtDesc(UUID productId, Pageable pageable);

    /** The rating summary shown alongside a product's reviews. */
    @Query("SELECT COALESCE(AVG(r.rating), 0) FROM ProductReview r WHERE r.productId = :productId")
    double averageRatingFor(@Param("productId") UUID productId);

    long countByProductId(UUID productId);

    /** Which of these order lines already have a review -- drives the "reviewed" badge. */
    @Query("SELECT r.orderItemId FROM ProductReview r WHERE r.orderItemId IN :ids")
    List<UUID> reviewedItemIdsIn(@Param("ids") Collection<UUID> ids);
}
