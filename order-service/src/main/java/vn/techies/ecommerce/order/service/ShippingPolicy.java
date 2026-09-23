package vn.techies.ecommerce.order.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Flat shipping fee, waived above a threshold. Hardcoded policy: no shipping service exists
 * in this project and inventing one is out of scope (docs/SPEC-order.md).
 */
@Component
public class ShippingPolicy {

    private final BigDecimal flatFee;
    private final BigDecimal freeThreshold;

    public ShippingPolicy(@Value("${techies.shipping.flat-fee:30000}") BigDecimal flatFee,
                          @Value("${techies.shipping.free-threshold:500000}") BigDecimal freeThreshold) {
        this.flatFee = flatFee;
        this.freeThreshold = freeThreshold;
    }

    public BigDecimal feeFor(BigDecimal subtotal) {
        return subtotal.compareTo(freeThreshold) >= 0 ? BigDecimal.ZERO : flatFee;
    }
}
