package vn.techies.ecommerce.order.service.payment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * A mock refund processor. There is no provider, no network call and no card data — see
 * docs/EXTENSIONS.md for what a real integration would require.
 *
 * <p>This used to simulate charging as well, because checkout settled payment inline. The app
 * now takes the customer to a payment screen and reports the outcome, so the backend never
 * charges anything: a card payment is confirmed by {@code PaymentService} and COD collects at
 * the door. Only the refund on cancellation is still ours to model.
 */
@Component
public class PaymentSimulator {

    private static final Logger log = LoggerFactory.getLogger(PaymentSimulator.class);

    /** Refund for a cancelled order. Mocked, and always succeeds. */
    public void refund(String orderRef, BigDecimal amount) {
        log.info("Simulated refund of {} for order {}", amount, orderRef);
    }
}
