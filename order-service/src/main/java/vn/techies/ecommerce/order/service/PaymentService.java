package vn.techies.ecommerce.order.service;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.techies.ecommerce.common.error.ApiException;
import vn.techies.ecommerce.common.error.ErrorCode;
import vn.techies.ecommerce.order.api.dto.OrderDtos.OrderResponse;
import vn.techies.ecommerce.order.api.dto.OrderDtos.PaymentConfirmationRequest;
import vn.techies.ecommerce.order.client.InventoryClient;
import vn.techies.ecommerce.order.domain.FailureCode;
import vn.techies.ecommerce.order.domain.Order;
import vn.techies.ecommerce.order.domain.OrderItem;
import vn.techies.ecommerce.order.domain.OrderStatus;
import vn.techies.ecommerce.order.domain.PaymentMethod;
import vn.techies.ecommerce.order.domain.SagaStepStatus;
import vn.techies.ecommerce.order.repository.OrderRepository;
import vn.techies.ecommerce.order.service.payment.PaymentOutcome;

import java.util.List;
import java.util.UUID;

/**
 * Finishes a checkout that stopped to let the customer pay.
 *
 * <p>This is the second half of the saga. Stock was already taken when the order was placed,
 * so there are only two ways out: confirm the order, or put the stock back. Both are terminal
 * and both are recorded in the saga trail, exactly as the inline charge used to be.
 *
 * <p><b>Idempotent by design.</b> A phone loses signal, the app retries, the customer taps
 * twice: the same outcome reported again returns the same order rather than erroring. Only a
 * genuine contradiction — reporting failure for an order already paid — is refused, because
 * that is a different operation and the customer wants {@code /cancel}.
 *
 * <p><b>The result is reported by the client</b>, which means it is only as trustworthy as the
 * app. Anyone holding a valid token for an order can call this and mark it paid without
 * paying. That is acceptable for a demo with a mock payment screen and is documented in
 * docs/SECURITY-NOTES.md; a real deployment would take the outcome from a signed provider
 * callback instead.
 */
@Service
@RequiredArgsConstructor
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final OrderRepository orders;
    private final CartService cartService;
    private final StockCompensator stockCompensator;
    private final SagaRecorder sagaRecorder;

    @Transactional
    public OrderResponse confirmPayment(UUID orderId, UUID userId,
                                        PaymentConfirmationRequest request) {
        Order order = orders.findById(orderId)
                .orElseThrow(() -> new ApiException(ErrorCode.ORDER_NOT_FOUND, "Order not found"));
        if (!order.getUserId().equals(userId)) {
            throw ApiException.forbidden("you may only pay for your own orders");
        }
        if (order.getPaymentMethod() == PaymentMethod.COD) {
            throw new ApiException(ErrorCode.ORDER_NOT_PAYABLE,
                    "This is a cash on delivery order, there is nothing to pay now");
        }

        if (!order.isAwaitingPayment()) {
            return alreadyResolved(order, request.result());
        }

        return request.result() == PaymentOutcome.SUCCESS
                ? settle(order, request.transactionRef(), userId)
                : release(order, request.failureReason());
    }

    /** The customer paid: confirm the order and take what they bought out of the cart. */
    private OrderResponse settle(Order order, String transactionRef, UUID userId) {
        order.confirm(transactionRef);
        sagaRecorder.record(order.getId(), "6-CHARGE_PAYMENT", SagaStepStatus.SUCCESS,
                transactionRef == null ? "paid" : "paid, ref " + transactionRef);

        cartService.removeByProductIds(userId,
                order.getItems().stream().map(OrderItem::getProductId).toList());
        sagaRecorder.record(order.getId(), "7-CONFIRM_ORDER", SagaStepStatus.SUCCESS,
                "cart cleared");

        log.info("Order {} paid and confirmed (ref {})", order.getOrderRef(), transactionRef);
        return OrderService.toResponse(order);
    }

    /** The payment did not happen: give the stock back and fail the order. */
    private OrderResponse release(Order order, String reason) {
        sagaRecorder.record(order.getId(), "6-CHARGE_PAYMENT", SagaStepStatus.FAILED,
                reason == null ? "reported as failed by the app" : reason);
        stockCompensator.restore(order.getId(), order.getOrderRef(), stockLines(order));
        order.fail(FailureCode.PAYMENT_FAILED);

        log.warn("ORDER FAILED {} -> PAYMENT_FAILED ({})", order.getOrderRef(), reason);
        return OrderService.toResponse(order);
    }

    /**
     * The order already left PENDING. Repeating the outcome it reached is a no-op; claiming
     * the opposite is refused.
     */
    private OrderResponse alreadyResolved(Order order, PaymentOutcome reported) {
        boolean paid = order.getStatus() == OrderStatus.CONFIRMED;
        boolean agrees = (reported == PaymentOutcome.SUCCESS) == paid;

        if (agrees) {
            log.debug("Payment for {} reported again as {}, already {}", order.getOrderRef(),
                    reported, order.getStatus());
            return OrderService.toResponse(order);
        }

        // The common real case: payment succeeded but the app only reported it after the
        // sweeper had already expired the order and returned the stock. Confirming now would
        // sell stock that has been given back to someone else.
        throw new ApiException(ErrorCode.ORDER_NOT_PAYABLE,
                "This order is already " + order.getStatus() + " and cannot be "
                        + (reported == PaymentOutcome.SUCCESS ? "paid" : "failed") + " now");
    }

    private static List<InventoryClient.StockLine> stockLines(Order order) {
        return order.getItems().stream()
                .map(i -> new InventoryClient.StockLine(i.getProductId(), i.getQuantity()))
                .toList();
    }
}
