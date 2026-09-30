package vn.techies.ecommerce.catalog.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import vn.techies.ecommerce.catalog.domain.ProductSpec;

import java.util.List;
import java.util.UUID;

public interface ProductSpecRepository extends JpaRepository<ProductSpec, UUID> {

    List<ProductSpec> findByProductIdOrderByDisplayOrderAsc(UUID productId);
}
