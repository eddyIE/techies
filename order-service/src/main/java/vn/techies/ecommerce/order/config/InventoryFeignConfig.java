package vn.techies.ecommerce.order.config;

import feign.Retryer;
import org.springframework.context.annotation.Bean;

/**
 * Retry policy for inventory calls only.
 *
 * <p>Safe because every inventory operation is idempotent on {@code (order_ref, type)}: a
 * retried deduct or restore returns the original movement rather than moving stock twice.
 * Worth having because a dropped compensation is the worst outcome in the whole saga — it
 * strands stock — so one retry materially reduces that risk.
 *
 * <p>Scope: this retries <em>transport</em> faults only — connection resets, connect and read
 * timeouts. Feign's default ErrorDecoder raises a RetryableException for those but not for an
 * HTTP error response, so a 409 INSUFFICIENT_STOCK or a 503 is passed straight through as a
 * decided answer. That is the intended boundary and is pinned by InventoryFeignRetryTest.
 *
 * <p>Deliberately not a {@code @Configuration} class: Feign instantiates it per client, and
 * annotating it would apply this retryer to every Feign client in the service. identity and
 * catalog calls are not retried.
 */
public class InventoryFeignConfig {

    @Bean
    public Retryer inventoryRetryer() {
        // 2 attempts total: the original plus one retry.
        return new Retryer.Default(100, 1000, 2);
    }
}
