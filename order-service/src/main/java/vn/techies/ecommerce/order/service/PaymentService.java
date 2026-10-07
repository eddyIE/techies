package vn.techies.ecommerce.order.service;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.techies.ecommerce.common.error.ApiException;
import vn.techies.ecommerce.common.error.ErrorCode;
import vn.techies.ecommerce.order.api.dto.OrderDtos.CartRestoreResponse;
import vn.techies.ecommerce.order.api.dto.OrderDtos.PaymentConfirmationRequest;
import vn.techies.ecommerce.order.api.dto.OrderDtos.PaymentResultResponse;
import vn.techies.ecommerce.order.client.InventoryClient;
import vn.techies.ecommerce.order.domain.FailureCode;
import vn.techies.ecommerce.order.domain.Order;
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
    private final VoucherCompensator voucherCompensator;
    private final SagaRecorder sagaRecorder;

    @Transactional
    public PaymentResultResponse confirmPayment(UUID orderId, UUID userId,
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
                ? settle(order, request.transactionRef())
                : declined(order, request.failureReason());
    }

    /**
     * Times out an unpaid order: releases its stock and puts its lines back in the cart.
     *
     * <p>Used by {@code PendingPaymentSweeper}. Safe on an order that is no longer awaiting
     * payment — it does nothing, so a sweep racing a late payment call cannot restore the
     * same stock twice.
     */
    @Transactional
    public void releaseExpired(UUID orderId, String reason) {
        Order order = orders.findById(orderId).orElseThrow();
        if (order.isAwaitingPayment()) {
            release(order, reason, true);
        }
    }

    /**
     * Releases an unpaid order that the customer has just replaced with a new one.
     *
     * <p>Unlike an expiry this does <b>not</b> refill the cart: the checkout that supersedes
     * it has already emptied those lines into the new order, and putting them back would
     * duplicate goods the customer is in the middle of buying.
     */
    @Transactional
    public void releaseSuperseded(UUID orderId, String reason) {
        Order order = orders.findById(orderId).orElseThrow();
        if (order.isAwaitingPayment()) {
            release(order, reason, false);
        }
    }

    /** The customer paid. The cart was emptied at checkout, so there is nothing to clear. */
    private PaymentResultResponse settle(Order order, String transactionRef) {
        order.confirm(transactionRef);
        sagaRecorder.record(order.getId(), "6-CHARGE_PAYMENT", SagaStepStatus.SUCCESS,
                transactionRef == null ? "paid" : "paid, ref " + transactionRef);
        sagaRecorder.record(order.getId(), "7-CONFIRM_ORDER", SagaStepStatus.SUCCESS, "confirmed");

        log.info("Order {} paid and confirmed (ref {})", order.getOrderRef(), transactionRef);
        return new PaymentResultResponse(OrderService.toResponse(order, java.util.Set.of()), null);
    }

    /** The payment did not happen: release the stock and hand the lines back to the cart. */
    private PaymentResultResponse declined(Order order, String reason) {
        CartService.RestoreSummary restored = release(order, reason, true);
        return new PaymentResultResponse(OrderService.toResponse(order, java.util.Set.of()),
                new CartRestoreResponse(restored.linesReturned(), restored.unavailable()));
    }

    /**
     * The one place an unpaid order is undone. Stock goes back first, so the cart restore's
     * availability check can see the units this order was holding.
     */
    private CartService.RestoreSummary release(Order order, String reason, boolean returnToCart) {
        sagaRecorder.record(order.getId(), "6-CHARGE_PAYMENT", SagaStepStatus.FAILED,
                reason == null ? "reported as failed by the app" : reason);
        stockCompensator.restore(order.getId(), order.getOrderRef(), stockLines(order));
        voucherCompensator.releaseIfHeld(order);
        order.fail(FailureCode.PAYMENT_FAILED);
        log.warn("ORDER FAILED {} -> PAYMENT_FAILED ({})", order.getOrderRef(), reason);

        if (!returnToCart) {
            return new CartService.RestoreSummary(0, List.of());
        }
        return cartService.restoreFromOrder(order.getUserId(), order.getItems().stream()
                .map(i -> new CartService.RestoreLine(i.getProductId(), i.getProductName(),
                        i.getQuantity()))
                .toList());
    }

    /**
     * The order already left PENDING. Repeating the outcome it reached is a no-op; claiming
     * the opposite is refused.
     */
    private PaymentResultResponse alreadyResolved(Order order, PaymentOutcome reported) {
        boolean paid = order.getStatus() == OrderStatus.CONFIRMED;
        boolean agrees = (reported == PaymentOutcome.SUCCESS) == paid;

        if (agrees) {
            log.debug("Payment for {} reported again as {}, already {}", order.getOrderRef(),
                    reported, order.getStatus());
            return new PaymentResultResponse(OrderService.toResponse(order, java.util.Set.of()), null);
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
