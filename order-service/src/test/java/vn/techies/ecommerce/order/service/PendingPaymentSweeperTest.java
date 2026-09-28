package vn.techies.ecommerce.order.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import vn.techies.ecommerce.order.AbstractPostgresTest;
import vn.techies.ecommerce.order.api.dto.CartDtos.AddCartItemRequest;
import vn.techies.ecommerce.order.api.dto.OrderDtos.CheckoutRequest;
import vn.techies.ecommerce.order.client.CatalogClient;
import vn.techies.ecommerce.order.client.IdentityClient;
import vn.techies.ecommerce.order.client.InventoryClient;
import vn.techies.ecommerce.order.domain.FailureCode;
import vn.techies.ecommerce.order.domain.Order;
import vn.techies.ecommerce.order.domain.OrderStatus;
import vn.techies.ecommerce.order.domain.PaymentMethod;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Stock is taken when the order is placed, so a customer who never finishes paying is holding
 * inventory nobody can buy. These are the cases that stop the catalogue bleeding availability.
 */
@SpringBootTest
class PendingPaymentSweeperTest extends AbstractPostgresTest {

    private static final UUID PRODUCT = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");

    @Autowired
    private CheckoutSagaOrchestrator saga;
    @Autowired
    private PendingPaymentSweeper sweeper;
    @Autowired
    private CartService cartService;
    @Autowired
    private OrderService orderService;
    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private IdentityClient identityClient;
    @MockitoBean
    private CatalogClient catalogClient;
    @MockitoBean
    private InventoryClient inventoryClient;

    private UUID userId;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        given(identityClient.getAddress(any(), any())).willReturn(
                new IdentityClient.AddressSnapshot(UUID.randomUUID(), "A", "0901234567",
                        "1 Le Loi", "W", "D", "HCM", true));
        given(catalogClient.batch(any())).willReturn(List.of(new CatalogClient.ProductSnapshot(
                PRODUCT, "Sản phẩm", new BigDecimal("100000.00"), "t", true)));
        given(inventoryClient.deduct(any()))
                .willReturn(new InventoryClient.MovementResponse("r", UUID.randomUUID(), true, false));
        given(inventoryClient.restore(any()))
                .willReturn(new InventoryClient.MovementResponse("r", UUID.randomUUID(), false, true));
    }

    private Order placeCardOrder() {
        cartService.add(userId, new AddCartItemRequest(PRODUCT, 1));
        return saga.checkout(userId, new CheckoutRequest(UUID.randomUUID(), PaymentMethod.MOCK_CARD, null));
    }

    /** Backdates the order rather than waiting fifteen real minutes for the window to pass. */
    private void ageBy(Order order, int minutes) {
        jdbc.update("UPDATE orders SET created_at = ? WHERE id = ?",
                Timestamp.from(Instant.now().minus(minutes, ChronoUnit.MINUTES)), order.getId());
    }

    @Test
    @DisplayName("an abandoned payment is failed and its stock returned once the window passes")
    void expiresAbandonedPayment() {
        Order order = placeCardOrder();
        ageBy(order, 30);

        sweeper.expireAbandonedPayments();

        var after = orderService.detail(order.getId(), userId);
        assertThat(after.status()).isEqualTo(OrderStatus.FAILED);
        assertThat(after.failureCode()).isEqualTo(FailureCode.PAYMENT_FAILED);
        verify(inventoryClient).restore(any());
    }

    @Test
    @DisplayName("an order still inside the window is left alone, so nobody is expired mid-payment")
    void leavesRecentOrdersAlone() {
        Order order = placeCardOrder();

        sweeper.expireAbandonedPayments();

        assertThat(orderService.detail(order.getId(), userId).status()).isEqualTo(OrderStatus.AWAITING_PAYMENT);
        verify(inventoryClient, never()).restore(any());
    }

    @Test
    @DisplayName("a COD order is never swept: it is confirmed at checkout and owes nothing now")
    void ignoresCodOrders() {
        cartService.add(userId, new AddCartItemRequest(PRODUCT, 1));
        Order cod = saga.checkout(userId, new CheckoutRequest(UUID.randomUUID(), PaymentMethod.COD, null));
        ageBy(cod, 30);

        sweeper.expireAbandonedPayments();

        assertThat(orderService.detail(cod.getId(), userId).status()).isEqualTo(OrderStatus.CONFIRMED);
        verify(inventoryClient, never()).restore(any());
    }

    @Test
    @DisplayName("sweeping twice restores the stock only once")
    void sweepingIsIdempotent() {
        Order order = placeCardOrder();
        ageBy(order, 30);

        sweeper.expireAbandonedPayments();
        sweeper.expireAbandonedPayments();

        verify(inventoryClient).restore(any());
    }
}
