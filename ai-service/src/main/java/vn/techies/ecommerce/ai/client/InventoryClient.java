package vn.techies.ecommerce.ai.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.UUID;

@FeignClient(name = "inventory-service", path = "/stock")
public interface InventoryClient {

    @GetMapping("/{productId}")
    StockResponse getStock(@PathVariable("productId") UUID productId);

    record StockResponse(UUID productId, int available, boolean inStock) {
    }
}
