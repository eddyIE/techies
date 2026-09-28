package vn.techies.ecommerce.order.service;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import vn.techies.ecommerce.order.client.InventoryClient;
import vn.techies.ecommerce.order.domain.SagaStepStatus;

import java.util.List;
import java.util.UUID;

/**
 * Returns the stock an order took, and records the attempt either way.
 *
 * <p>Extracted because three callers now need it: the checkout saga when a step after the
 * deduction fails, {@code PaymentService} when the customer's payment is declined, and
 * {@code PendingPaymentSweeper} when nobody ever finishes paying. They used to be one path
 * because payment was settled inside checkout.
 *
 * <p>Best-effort but never silent: if restore itself fails the caller still marks the order
 * FAILED and the stranded reference is written to saga_steps, so it can be replayed by hand.
 * restore is idempotent, so replay is safe.
 */
@Component
@RequiredArgsConstructor
public class StockCompensator {

    private static final Logger log = LoggerFactory.getLogger(StockCompensator.class);

    private final InventoryClient inventoryClient;
    private final SagaRecorder sagaRecorder;

    /** @return whether the stock actually made it back. */
    public boolean restore(UUID orderId, String orderRef, List<InventoryClient.StockLine> lines) {
        try {
            inventoryClient.restore(new InventoryClient.StockMovementRequest(orderRef, lines));
            sagaRecorder.record(orderId, "5-DEDUCT_STOCK", SagaStepStatus.COMPENSATED,
                    "stock restored");
            log.info("Compensated order {}: stock restored", orderRef);
            return true;
        } catch (Exception ex) {
            sagaRecorder.record(orderId, "5-DEDUCT_STOCK", SagaStepStatus.FAILED,
                    "COMPENSATION FAILED, stock stranded for " + orderRef + ": " + ex);
            log.error("Compensation failed for order {}; stock is stranded and needs a manual "
                    + "restore replay", orderRef, ex);
            return false;
        }
    }
}
