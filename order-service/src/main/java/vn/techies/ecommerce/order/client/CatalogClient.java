package vn.techies.ecommerce.order.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@FeignClient(name = "catalog-service", path = "/internal/products")
public interface CatalogClient {

    /** One call per checkout, never one per line. */
    @PostMapping("/batch")
    List<ProductSnapshot> batch(@RequestBody BatchRequest request);

    record BatchRequest(List<UUID> productIds) {
    }

    record ProductSnapshot(UUID id, String name, BigDecimal price, String thumbnailUrl, boolean active) {
    }
}
