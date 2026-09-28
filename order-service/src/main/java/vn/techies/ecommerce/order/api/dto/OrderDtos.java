package vn.techies.ecommerce.order.api.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import vn.techies.ecommerce.order.domain.FailureCode;
import vn.techies.ecommerce.order.domain.OrderStatus;
import vn.techies.ecommerce.order.domain.PaymentMethod;
import vn.techies.ecommerce.order.domain.PaymentStatus;
import vn.techies.ecommerce.order.service.payment.PaymentOutcome;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class OrderDtos {

    private OrderDtos() {
    }

    /**
     * @param cartItemIds the cart lines to buy, for the cart screen's per-line selection.
     *                    Omit or send null to check out the whole cart, which is what every
     *                    existing client does. An empty array is rejected: it asks to buy
     *                    nothing, which is a client bug rather than a whole-cart checkout.
     *                    Only the selected lines are removed on success; the rest stay.
     */
    public record CheckoutRequest(
            @NotNull UUID addressId,
            @NotNull PaymentMethod paymentMethod,
            @Size(min = 1, message = "select at least one item, or omit the field for the whole cart")
            List<UUID> cartItemIds) {
    }

    /**
     * What the app reports once the customer has finished at the payment screen.
     *
     * @param result         SUCCESS confirms the order, FAILED releases its stock.
     * @param transactionRef the provider's transaction id. Recorded on the order so a payment
     *                       can be traced back to a real transaction; expected on SUCCESS.
     * @param failureReason  free text shown in the logs and the saga trail on FAILED.
     */
    public record PaymentConfirmationRequest(
            @NotNull PaymentOutcome result,
            @Size(max = 64) String transactionRef,
            @Size(max = 200) String failureReason) {
    }

    public record ShippingAddressResponse(String recipientName, String phone, String line1,
                                          String ward, String district, String province) {
    }

    /**
     * @param thumbnailUrl the product image as it was at checkout, or null for orders placed
     *                     before this was recorded — render a placeholder in that case.
     */
    public record OrderItemResponse(UUID productId, String productName, BigDecimal unitPrice,
                                    int quantity, BigDecimal lineTotal, String thumbnailUrl) {
    }

    public record OrderResponse(UUID id, String orderRef, OrderStatus status, FailureCode failureCode,
                                BigDecimal subtotal, BigDecimal shippingFee, BigDecimal total,
                                PaymentMethod paymentMethod, PaymentStatus paymentStatus,
                                String paymentRef, ShippingAddressResponse shippingAddress,
                                List<OrderItemResponse> items, Instant createdAt) {
    }

    public record OrderSummary(UUID id, String orderRef, OrderStatus status, FailureCode failureCode,
                               BigDecimal total, int itemCount, Instant createdAt) {
    }

    /**
     * Returned with HTTP 200 even when the order FAILED: the mobile app needs the order id to
     * drive the Payment Result screen and its retry loop back to Checkout.
     */
    public record CheckoutResponse(OrderResponse order, String message) {
    }

    public record PageResponse<T>(List<T> content, int page, int size,
                                  long totalElements, int totalPages) {
    }
}
