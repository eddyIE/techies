package vn.techies.ecommerce.order.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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
            List<UUID> cartItemIds,
            @Size(max = 32) String couponCode) {
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

    /**
     * Moves an order to a chosen status, for demonstrating the lifecycle without a management
     * app. See {@code OrderService#updateStatus}.
     *
     * @param status the status to move to. PENDING is refused: it is an internal saga state
     *               the app has no screen for.
     */
    public record OrderStatusUpdateRequest(@NotNull OrderStatus status) {
    }

    /**
     * @param cartRestore what went back into the cart, or null when nothing was restored
     *                    (a successful payment, or an outcome reported twice).
     */
    public record PaymentResultResponse(OrderResponse order, CartRestoreResponse cartRestore) {
    }

    /**
     * @param linesReturned how many of the order's lines went back into the cart.
     * @param unavailable   the names of the lines that did not, because the product is
     *                      delisted or someone else bought the stock this order was holding.
     *                      Show these to the customer: their cart is not what it was.
     */
    public record CartRestoreResponse(int linesReturned, List<String> unavailable) {
    }

    /**
     * What the post-checkout review page submits. Several lines at once, because that page
     * lists every product in the order and submits them together.
     */
    public record WriteReviewsRequest(
            @NotNull @Size(min = 1, max = 50) @Valid List<ReviewEntry> reviews) {
    }

    public record ReviewEntry(
            @NotNull UUID orderItemId,
            @NotNull @Min(1) @Max(5) Integer rating,
            @Size(max = 1000) String comment) {
    }

    /** @param averageRating 0 when there are no reviews yet, never null. */
    public record ProductReviewsResponse(double averageRating, long total,
                                         List<ReviewResponse> content) {
    }

    public record ReviewResponse(UUID productId, String authorName, int rating, String comment,
                                 Instant createdAt) {
    }

    public record ShippingAddressResponse(String recipientName, String phone, String line1,
                                          String ward, String district, String province) {
    }

    /**
     * @param thumbnailUrl the product image as it was at checkout, or null for orders placed
     *                     before this was recorded — render a placeholder in that case.
     */
    /**
     * @param id       the order line's own id. The review endpoint keys on it, because a review
     *                 belongs to a purchase rather than to a product.
     * @param reviewed whether this line has already been reviewed.
     */
    public record OrderItemResponse(UUID id, UUID productId, String productName,
                                    BigDecimal unitPrice, int quantity, BigDecimal lineTotal,
                                    String thumbnailUrl, boolean reviewed) {
    }

    public record OrderResponse(UUID id, String orderRef, OrderStatus status, FailureCode failureCode,
                                BigDecimal subtotal, BigDecimal shippingFee, BigDecimal total,
                                String couponCode, BigDecimal discount,
                                PaymentMethod paymentMethod, PaymentStatus paymentStatus,
                                String paymentRef, ShippingAddressResponse shippingAddress,
                                List<OrderItemResponse> items, Instant createdAt) {
    }

    /**
     * @param firstItem the first line, so an order row can show a picture and a name without
     *                  the app fetching every order's detail just to render the list.
     * @param reviewed  whether every line of this order has been reviewed. Only ever true for
     *                  a CONFIRMED or COMPLETED order: nothing else is reviewable.
     */
    public record OrderSummary(UUID id, String orderRef, OrderStatus status, FailureCode failureCode,
                               BigDecimal total, int itemCount, OrderLinePreview firstItem,
                               boolean reviewed, Instant createdAt) {
    }

    public record OrderLinePreview(UUID productId, String productName, String thumbnailUrl,
                                   int quantity) {
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
