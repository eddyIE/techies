package vn.techies.ecommerce.order.service.payment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import vn.techies.ecommerce.order.domain.PaymentMethod;

import java.math.BigDecimal;

/**
 * A mock payment processor. There is no provider, no network call and no card data — see
 * docs/EXTENSIONS.md for what a real integration would require.
 *
 * <p>The outcome is driven by the checkout request rather than configuration, so a demo can
 * show a success and a decline back to back without a restart.
 */
@Component
public class PaymentSimulator {

    private static final Logger log = LoggerFactory.getLogger(PaymentSimulator.class);

    /** How long the simulated processor "thinks" before approving. */
    private static final long APPROVAL_DELAY_MS = 200;

    public PaymentResult charge(String orderRef, BigDecimal amount,
                                PaymentMethod method, PaymentSimulation simulation) {
        if (method == PaymentMethod.COD) {
            // Nothing is charged now; the courier collects on delivery.
            log.info("COD order {} for {} needs no upfront payment", orderRef, amount);
            return PaymentResult.approve();
        }

        PaymentSimulation effective = simulation == null ? PaymentSimulation.SUCCESS : simulation;
        log.info("Simulating {} card payment for order {} ({})", effective, orderRef, amount);

        return switch (effective) {
            case SUCCESS -> {
                sleep(APPROVAL_DELAY_MS);
                yield PaymentResult.approve();
            }
            case DECLINED -> {
                sleep(APPROVAL_DELAY_MS);
                yield PaymentResult.decline("Card declined by issuer");
            }
            case TIMEOUT -> {
                // Treated as a decline: an unconfirmed payment must not confirm an order.
                sleep(APPROVAL_DELAY_MS);
                yield PaymentResult.decline("Payment processor timed out");
            }
        };
    }

    /** Refund for a cancelled order. Mocked, and always succeeds. */
    public void refund(String orderRef, BigDecimal amount) {
        log.info("Simulated refund of {} for order {}", amount, orderRef);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
