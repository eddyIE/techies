package vn.techies.ecommerce.order.api;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.techies.ecommerce.common.security.CurrentUser;
import vn.techies.ecommerce.common.security.UserPrincipal;
import vn.techies.ecommerce.order.api.dto.OrderDtos.CheckoutRequest;
import vn.techies.ecommerce.order.api.dto.OrderDtos.CheckoutResponse;
import vn.techies.ecommerce.order.api.dto.OrderDtos.OrderResponse;
import vn.techies.ecommerce.order.api.dto.OrderDtos.OrderStatusUpdateRequest;
import vn.techies.ecommerce.order.api.dto.OrderDtos.OrderSummary;
import vn.techies.ecommerce.order.api.dto.OrderDtos.PageResponse;
import vn.techies.ecommerce.order.api.dto.OrderDtos.PaymentConfirmationRequest;
import vn.techies.ecommerce.order.api.dto.OrderDtos.PaymentResultResponse;
import vn.techies.ecommerce.order.api.dto.OrderDtos.ProductReviewsResponse;
import vn.techies.ecommerce.order.api.dto.OrderDtos.WriteReviewsRequest;
import vn.techies.ecommerce.order.domain.Order;
import vn.techies.ecommerce.order.domain.OrderStatus;
import vn.techies.ecommerce.order.service.CheckoutSagaOrchestrator;
import vn.techies.ecommerce.order.service.OrderService;
import vn.techies.ecommerce.order.service.PaymentService;
import vn.techies.ecommerce.order.service.ReviewService;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
class OrderController {

    private final CheckoutSagaOrchestrator checkoutSaga;
    private final OrderService orderService;
    private final PaymentService paymentService;
    private final ReviewService reviewService;

    /**
     * Returns 200 even when the order FAILED — the client reads {@code order.status} to decide
     * between the Order Success and Payment Result screens, and needs the order id for both.
     * Only transport-level problems produce a 5xx.
     */
    @PostMapping("/checkout")
    CheckoutResponse checkout(@CurrentUser UserPrincipal principal,
                              @Valid @RequestBody CheckoutRequest request) {
        Order order = checkoutSaga.checkout(principal.userId(), request);
        // Re-read inside a transaction: the saga returns a detached entity whose item
        // collection is lazy, and serializing it directly would fail.
        OrderResponse response = orderService.detail(order.getId(), principal.userId());
        return new CheckoutResponse(response, messageFor(order));
    }

    @GetMapping("/orders")
    PageResponse<OrderSummary> list(@CurrentUser UserPrincipal principal,
                                    @RequestParam(required = false) OrderStatus status,
                                    @RequestParam(defaultValue = "0") int page,
                                    @RequestParam(defaultValue = "20") int size) {
        return orderService.list(principal.userId(), status, page, size);
    }

    @GetMapping("/orders/{id}")
    OrderResponse detail(@CurrentUser UserPrincipal principal, @PathVariable UUID id) {
        return orderService.detail(id, principal.userId());
    }

    /**
     * Reports the outcome of the payment the app just took the customer through.
     *
     * <p>Idempotent: reporting the same outcome again returns the same order. Reporting the
     * opposite of one already settled is a 409, because that is a different operation — a
     * paid order is undone with {@code /cancel}, and an expired one has already given its
     * stock back.
     *
     * <p>A failure also hands the order's lines back to the cart, and {@code cartRestore}
     * says what could not be returned.
     */
    @PostMapping("/orders/{id}/payment")
    PaymentResultResponse confirmPayment(@CurrentUser UserPrincipal principal, @PathVariable UUID id,
                                         @Valid @RequestBody PaymentConfirmationRequest request) {
        return paymentService.confirmPayment(id, principal.userId(), request);
    }

    /**
     * Submits the post-checkout review page. Several lines at once, because that page lists
     * every product in the order and submits them together.
     *
     * <p>Returns the order, so the app immediately sees which lines are now reviewed and
     * whether anything is still outstanding.
     */
    @PostMapping("/orders/{id}/reviews")
    OrderResponse writeReviews(@CurrentUser UserPrincipal principal, @PathVariable UUID id,
                               @Valid @RequestBody WriteReviewsRequest request) {
        return reviewService.write(id, principal.userId(), request);
    }

    /**
     * A product's reviews and its rating. Routed here rather than to catalog-service because
     * the reviews live with the purchases that entitle them — see docs/SPEC-order.md.
     */
    @GetMapping("/products/{productId}/reviews")
    ProductReviewsResponse productReviews(@PathVariable UUID productId,
                                          @RequestParam(defaultValue = "0") int page,
                                          @RequestParam(defaultValue = "10") int size) {
        return reviewService.forProduct(productId, page, size);
    }

    /**
     * Demo only: moves an order to any status so the lifecycle can be shown without a
     * management app. Does not touch stock — see {@code OrderService#updateStatus}.
     */
    @PutMapping("/orders/{id}/status")
    OrderResponse updateStatus(@CurrentUser UserPrincipal principal, @PathVariable UUID id,
                               @Valid @RequestBody OrderStatusUpdateRequest request) {
        return orderService.updateStatus(id, principal.userId(), request.status());
    }

    @PostMapping("/orders/{id}/cancel")
    OrderResponse cancel(@CurrentUser UserPrincipal principal, @PathVariable UUID id) {
        return orderService.cancel(id, principal.userId());
    }

    private static String messageFor(Order order) {
        return switch (order.getStatus()) {
            case CONFIRMED -> "Order placed successfully";
            case AWAITING_PAYMENT -> "Order placed, complete the payment to confirm it";
            case FAILED -> switch (order.getFailureCode()) {
                case OUT_OF_STOCK -> "Some items are no longer in stock";
                case PAYMENT_FAILED -> "Payment was declined, your cart has been kept";
                case SERVICE_UNAVAILABLE -> "We could not complete your order, please try again";
            };
            default -> "Order is being processed";
        };
    }
}
