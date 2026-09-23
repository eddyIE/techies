package vn.techies.ecommerce.order.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import vn.techies.ecommerce.order.config.InventoryFeignConfig;

import java.util.List;
import java.util.UUID;

@FeignClient(name = "inventory-service", path = "/stock", configuration = InventoryFeignConfig.class)
public interface InventoryClient {

    @PostMapping("/deduct")
    MovementResponse deduct(@RequestBody StockMovementRequest request);

    @PostMapping("/restore")
    MovementResponse restore(@RequestBody StockMovementRequest request);

    @GetMapping("/{productId}")
    StockResponse getStock(@PathVariable("productId") UUID productId);

    record StockLine(UUID productId, int quantity) {
    }

    record StockMovementRequest(String orderRef, List<StockLine> items) {
    }

    record MovementResponse(String orderRef, UUID movementId, boolean deducted, boolean restored) {
    }

    record StockResponse(UUID productId, int available, boolean inStock) {
    }
}
