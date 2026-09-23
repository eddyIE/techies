package vn.techies.ecommerce.inventory.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

public final class StockDtos {

    private StockDtos() {
    }

    public record StockLine(@NotNull UUID productId, @Min(1) int quantity) {
    }

    public record StockMovementRequest(
            @NotBlank @Size(max = 20) String orderRef,
            @NotEmpty @Valid List<StockLine> items) {
    }

    public record DeductResponse(String orderRef, UUID movementId, boolean deducted) {
    }

    public record RestoreResponse(String orderRef, UUID movementId, boolean restored) {
    }

    public record StockResponse(UUID productId, int available, boolean inStock) {
    }
}
