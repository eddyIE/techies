package vn.techies.ecommerce.inventory.api;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.techies.ecommerce.inventory.api.dto.StockDtos.DeductResponse;
import vn.techies.ecommerce.inventory.api.dto.StockDtos.RestoreResponse;
import vn.techies.ecommerce.inventory.api.dto.StockDtos.StockMovementRequest;
import vn.techies.ecommerce.inventory.api.dto.StockDtos.StockResponse;
import vn.techies.ecommerce.inventory.service.StockService;

import java.util.UUID;

@RestController
@RequestMapping("/stock")
@RequiredArgsConstructor
class StockController {

    private final StockService stockService;

    /** The only publicly routed stock endpoint — product detail shows an in-stock badge. */
    @GetMapping("/{productId}")
    StockResponse get(@PathVariable UUID productId) {
        return stockService.get(productId);
    }

    @PostMapping("/deduct")
    DeductResponse deduct(@Valid @RequestBody StockMovementRequest request) {
        return stockService.deduct(request);
    }

    @PostMapping("/restore")
    RestoreResponse restore(@Valid @RequestBody StockMovementRequest request) {
        return stockService.restore(request);
    }
}
