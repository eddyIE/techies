package vn.techies.ecommerce.order.service.payment;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThatCode;

class PaymentSimulatorTest {

    private final PaymentSimulator simulator = new PaymentSimulator();

    @Test
    @DisplayName("a refund always succeeds, so cancelling never fails on the money")
    void refundAlwaysSucceeds() {
        assertThatCode(() -> simulator.refund("ORD-1", new BigDecimal("550000.00")))
                .doesNotThrowAnyException();
    }
}
