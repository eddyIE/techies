package vn.techies.ecommerce.order.service;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import vn.techies.ecommerce.order.client.InventoryClient;
import vn.techies.ecommerce.order.domain.FailureCode;
import vn.techies.ecommerce.order.domain.Order;
import vn.techies.ecommerce.order.domain.SagaStepStatus;
import vn.techies.ecommerce.order.repository.OrderRepository;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Expires checkouts nobody came back to pay for, and returns the stock they were holding.
 *
 * <p>Stock is deducted when the order is placed, so a customer who opens the payment screen
 * and closes the app holds that stock indefinitely. Without this the catalogue bleeds
 * availability on every abandoned checkout until someone reseeds inventory by hand — the
 * failure is silent, and looks like the shop genuinely selling out.
 *
 * <p>The window has to be comfortably longer than a real payment takes. Expiring an order
 * while the customer is still typing their card details would take the stock back and then
 * refuse the payment that follows, which is a far worse outcome than holding a few units for
 * a quarter of an hour.
 */
@Component
@RequiredArgsConstructor
public class PendingPaymentSweeper {

    private static final Logger log = LoggerFactory.getLogger(PendingPaymentSweeper.class);

    private final OrderRepository orders;
    private final PaymentService paymentService;

    @Value("${techies.payment.window-minutes:15}")
    private int windowMinutes;

    /**
     * Runs on a fixed delay rather than a fixed rate: if a sweep is slow because inventory is
     * struggling, the next one waits rather than piling on.
     */
    @Scheduled(fixedDelayString = "${techies.payment.sweep-interval-ms:60000}",
            initialDelayString = "${techies.payment.sweep-interval-ms:60000}")
    @Transactional
    public void expireAbandonedPayments() {
        Instant cutoff = Instant.now().minus(Duration.ofMinutes(windowMinutes));
        List<Order> expired = orders.findExpiredPendingPayments(cutoff);
        if (expired.isEmpty()) {
            return;
        }

        log.info("Expiring {} order(s) whose payment was never completed", expired.size());
        for (Order order : expired) {
            expire(order);
        }
    }

    private void expire(Order order) {
        paymentService.releaseExpired(order.getId(),
                "payment not completed within " + windowMinutes + " minutes");
    }
}
