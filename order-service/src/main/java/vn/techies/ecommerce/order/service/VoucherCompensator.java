package vn.techies.ecommerce.order.service;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import vn.techies.ecommerce.order.client.LoyaltyClient;
import vn.techies.ecommerce.order.domain.Order;
import vn.techies.ecommerce.order.domain.SagaStepStatus;

/**
 * Hands a loyalty voucher back, and records the attempt either way.
 *
 * <p>The mirror of {@link StockCompensator}, with the same three callers: the checkout saga
 * when the deduction fails, {@code PaymentService} when payment is declined or abandoned, and
 * {@code OrderService#cancel} when the customer changes their mind. Cancellation is the path
 * worth naming — nothing went wrong there, and leaving the voucher consumed would quietly
 * destroy a reward the customer earned.
 *
 * <p>Best-effort but never silent, again like stock: a failure leaves the order FAILED or
 * CANCELLED as it should be, and writes the stranded code to saga_steps for a manual replay.
 */
@Component
@RequiredArgsConstructor
public class VoucherCompensator {

    private static final Logger log = LoggerFactory.getLogger(VoucherCompensator.class);

    private final LoyaltyClient loyaltyClient;
    private final SagaRecorder sagaRecorder;

    /** Does nothing for an order whose discount was a coupon, or that had none. */
    public void releaseIfHeld(Order order) {
        if (!order.usedLoyaltyVoucher()) {
            return;
        }
        String code = order.getCouponCode();
        try {
            loyaltyClient.release(code, new LoyaltyClient.ReleaseRequest(order.getOrderRef()));
            sagaRecorder.record(order.getId(), "3-CONSUME_VOUCHER", SagaStepStatus.COMPENSATED,
                    "voucher " + code + " released");
            log.info("Compensated order {}: voucher {} released", order.getOrderRef(), code);
        } catch (Exception ex) {
            sagaRecorder.record(order.getId(), "3-CONSUME_VOUCHER", SagaStepStatus.FAILED,
                    "COMPENSATION FAILED, voucher " + code + " stranded: " + ex);
            log.error("Could not release voucher {} for order {}; it is stranded as consumed and "
                    + "needs a manual release replay", code, order.getOrderRef(), ex);
        }
    }
}
