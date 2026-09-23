package vn.techies.ecommerce.catalog.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import vn.techies.ecommerce.catalog.domain.ProductImage;

import java.util.List;
import java.util.UUID;

public interface ProductImageRepository extends JpaRepository<ProductImage, UUID> {

    List<ProductImage> findByProductIdOrderByDisplayOrderAsc(UUID productId);
}
