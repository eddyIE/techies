package vn.techies.ecommerce.order.api.dto;

import jakarta.validation.constraints.NotNull;
import vn.techies.ecommerce.order.domain.FailureCode;
import vn.techies.ecommerce.order.domain.OrderStatus;
import vn.techies.ecommerce.order.domain.PaymentMethod;
import vn.techies.ecommerce.order.domain.PaymentStatus;
import vn.techies.ecommerce.order.service.payment.PaymentSimulation;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class OrderDtos {

    private OrderDtos() {
    }

    /**
     * @param simulatePayment demo control for MOCK_CARD only, so both branches of the Order
     *                        Flow can be shown without restarting anything. Ignored for COD.
     */
    public record CheckoutRequest(
            @NotNull UUID addressId,
            @NotNull PaymentMethod paymentMethod,
            PaymentSimulation simulatePayment) {
    }

    public record ShippingAddressResponse(String recipientName, String phone, String line1,
                                          String ward, String district, String province) {
    }

    public record OrderItemResponse(UUID productId, String productName, BigDecimal unitPrice,
                                    int quantity, BigDecimal lineTotal) {
    }

    public record OrderResponse(UUID id, String orderRef, OrderStatus status, FailureCode failureCode,
                                BigDecimal subtotal, BigDecimal shippingFee, BigDecimal total,
                                PaymentMethod paymentMethod, PaymentStatus paymentStatus,
                                ShippingAddressResponse shippingAddress,
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
