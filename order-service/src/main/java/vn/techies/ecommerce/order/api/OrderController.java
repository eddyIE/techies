package vn.techies.ecommerce.order.api;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.techies.ecommerce.common.security.CurrentUser;
import vn.techies.ecommerce.common.security.UserPrincipal;
import vn.techies.ecommerce.order.api.dto.OrderDtos.CheckoutRequest;
import vn.techies.ecommerce.order.api.dto.OrderDtos.CheckoutResponse;
import vn.techies.ecommerce.order.api.dto.OrderDtos.OrderResponse;
import vn.techies.ecommerce.order.api.dto.OrderDtos.OrderSummary;
import vn.techies.ecommerce.order.api.dto.OrderDtos.PageResponse;
import vn.techies.ecommerce.order.domain.Order;
import vn.techies.ecommerce.order.domain.OrderStatus;
import vn.techies.ecommerce.order.service.CheckoutSagaOrchestrator;
import vn.techies.ecommerce.order.service.OrderService;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
class OrderController {

    private final CheckoutSagaOrchestrator checkoutSaga;
    private final OrderService orderService;

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

    @PostMapping("/orders/{id}/cancel")
    OrderResponse cancel(@CurrentUser UserPrincipal principal, @PathVariable UUID id) {
        return orderService.cancel(id, principal.userId());
    }

    private static String messageFor(Order order) {
        return switch (order.getStatus()) {
            case CONFIRMED -> "Order placed successfully";
            case FAILED -> switch (order.getFailureCode()) {
                case OUT_OF_STOCK -> "Some items are no longer in stock";
                case PAYMENT_FAILED -> "Payment was declined, your cart has been kept";
                case SERVICE_UNAVAILABLE -> "We could not complete your order, please try again";
            };
            default -> "Order is being processed";
        };
    }
}
