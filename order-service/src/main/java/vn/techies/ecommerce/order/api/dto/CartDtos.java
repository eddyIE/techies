package vn.techies.ecommerce.order.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public final class CartDtos {

    private CartDtos() {
    }

    public record AddCartItemRequest(
            @NotNull UUID productId,
            @Min(1) @Max(99) int quantity) {
    }

    /** Quantity 0 is allowed and means "remove this line". */
    public record UpdateCartItemRequest(@Min(0) @Max(99) int quantity) {
    }

    /**
     * @param available live stock, or null when inventory-service could not be reached.
     *                  The cart still renders in that case rather than failing outright.
     */
    public record CartItemResponse(UUID id, UUID productId, String name, BigDecimal unitPrice,
                                   int quantity, BigDecimal lineTotal, String thumbnailUrl,
                                   Integer available) {
    }

    public record CartResponse(List<CartItemResponse> items, BigDecimal subtotal, int itemCount) {
    }
}
