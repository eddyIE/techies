package vn.techies.ecommerce.order.service;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import vn.techies.ecommerce.order.domain.FailureCode;
import vn.techies.ecommerce.order.domain.Order;
import vn.techies.ecommerce.order.repository.OrderRepository;

import java.util.UUID;

/**
 * Order state transitions, each in its own committed transaction.
 *
 * <p>Separate from the orchestrator for the same reason as {@link SagaRecorder}: self-invoked
 * @Transactional methods do not go through the proxy and would have no effect.
 */
@Component
@RequiredArgsConstructor
public class OrderWriter {

    private static final Logger log = LoggerFactory.getLogger(OrderWriter.class);

    private final OrderRepository orders;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Order save(Order order) {
        return orders.save(order);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Order fail(UUID orderId, FailureCode code) {
        Order order = orders.findById(orderId).orElseThrow();
        order.fail(code);
        // WARN, not INFO: checkout answers 200 with a FAILED order, so this never
        // reaches GlobalExceptionHandler and would otherwise miss the error log.
        log.warn("ORDER FAILED {} -> {}", order.getOrderRef(), code);
        return orders.save(order);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Order confirm(UUID orderId) {
        Order order = orders.findById(orderId).orElseThrow();
        order.confirm();
        return orders.save(order);
    }
}
