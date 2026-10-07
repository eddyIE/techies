package vn.techies.ecommerce.order.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import vn.techies.ecommerce.order.domain.ProductReviewSummary;

import java.util.UUID;

public interface ProductReviewSummaryRepository extends JpaRepository<ProductReviewSummary, UUID> {
}
