package vn.techies.ecommerce.order.service.payment;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vn.techies.ecommerce.order.domain.PaymentMethod;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentSimulatorTest {

    private final PaymentSimulator simulator = new PaymentSimulator();
    private static final BigDecimal AMOUNT = new BigDecimal("550000.00");

    @Test
    @DisplayName("COD approves regardless of the simulation flag, since nothing is charged now")
    void codAlwaysApproves() {
        assertThat(simulator.charge("ORD-1", AMOUNT, PaymentMethod.COD, null).approved()).isTrue();
        assertThat(simulator.charge("ORD-1", AMOUNT, PaymentMethod.COD, PaymentSimulation.DECLINED)
                .approved()).as("COD ignores the card simulation").isTrue();
    }

    @Test
    @DisplayName("a card payment defaults to success when no simulation is requested")
    void cardDefaultsToSuccess() {
        PaymentResult result = simulator.charge("ORD-2", AMOUNT, PaymentMethod.MOCK_CARD, null);

        assertThat(result.approved()).isTrue();
        assertThat(result.reason()).isEqualTo("Approved");
    }

    @Test
    @DisplayName("SUCCESS approves")
    void successApproves() {
        assertThat(simulator.charge("ORD-3", AMOUNT, PaymentMethod.MOCK_CARD, PaymentSimulation.SUCCESS)
                .approved()).isTrue();
    }

    @Test
    @DisplayName("DECLINED is refused with a reason the client can show")
    void declinedIsRefused() {
        PaymentResult result = simulator.charge("ORD-4", AMOUNT, PaymentMethod.MOCK_CARD,
                PaymentSimulation.DECLINED);

        assertThat(result.approved()).isFalse();
        assertThat(result.reason()).isEqualTo("Card declined by issuer");
    }

    @Test
    @DisplayName("TIMEOUT is treated as a decline: an unconfirmed payment must not confirm an order")
    void timeoutIsTreatedAsDecline() {
        PaymentResult result = simulator.charge("ORD-5", AMOUNT, PaymentMethod.MOCK_CARD,
                PaymentSimulation.TIMEOUT);

        assertThat(result.approved()).isFalse();
        assertThat(result.reason()).contains("timed out");
    }
}
