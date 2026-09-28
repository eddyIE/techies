package vn.techies.ecommerce.order.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import vn.techies.ecommerce.common.error.ApiException;
import vn.techies.ecommerce.common.error.ErrorCode;
import vn.techies.ecommerce.order.AbstractPostgresTest;
import vn.techies.ecommerce.order.api.dto.CartDtos.AddCartItemRequest;
import vn.techies.ecommerce.order.api.dto.OrderDtos.CheckoutRequest;
import vn.techies.ecommerce.order.client.CatalogClient;
import vn.techies.ecommerce.order.client.IdentityClient;
import vn.techies.ecommerce.order.client.InventoryClient;
import vn.techies.ecommerce.order.domain.Order;
import vn.techies.ecommerce.order.domain.OrderStatus;
import vn.techies.ecommerce.order.domain.PaymentMethod;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

/**
 * COMPLETED is accepted by the schema and the API but nothing sets it, so these pin the two
 * things that would otherwise break silently: the value survives a round trip, and it is
 * treated as terminal.
 */
@SpringBootTest
class OrderStatusCompletedTest extends AbstractPostgresTest {

    private static final UUID PRODUCT = UUID.fromString("dddddddd-0000-0000-0000-000000000004");

    @Autowired
    private CheckoutSagaOrchestrator saga;
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

    private UUID confirmedOrder(UUID userId) {
        given(identityClient.getAddress(any(), any())).willReturn(
                new IdentityClient.AddressSnapshot(UUID.randomUUID(), "A", "0901234567",
                        "1 Le Loi", "W", "D", "HCM", true));
        given(catalogClient.batch(any())).willReturn(List.of(
                new CatalogClient.ProductSnapshot(PRODUCT, "Sản phẩm", new BigDecimal("100000.00"), "t", true)));
        given(inventoryClient.deduct(any()))
                .willReturn(new InventoryClient.MovementResponse("r", UUID.randomUUID(), true, false));

        cartService.add(userId, new AddCartItemRequest(PRODUCT, 1));
        Order order = saga.checkout(userId, new CheckoutRequest(UUID.randomUUID(), PaymentMethod.COD, null));
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        return order.getId();
    }

    @Test
    @DisplayName("the database accepts COMPLETED and it reads back through the API")
    void completedSurvivesARoundTrip() {
        UUID userId = UUID.randomUUID();
        UUID orderId = confirmedOrder(userId);

        jdbc.update("UPDATE orders SET status = 'COMPLETED' WHERE id = ?", orderId);

        assertThat(orderService.detail(orderId, userId).status()).isEqualTo(OrderStatus.COMPLETED);
    }

    @Test
    @DisplayName("filtering the order list by COMPLETED works")
    void filtersByCompleted() {
        UUID userId = UUID.randomUUID();
        UUID orderId = confirmedOrder(userId);
        jdbc.update("UPDATE orders SET status = 'COMPLETED' WHERE id = ?", orderId);

        assertThat(orderService.list(userId, OrderStatus.COMPLETED, 0, 20).content()).hasSize(1);
        assertThat(orderService.list(userId, OrderStatus.CONFIRMED, 0, 20).content()).isEmpty();
    }

    @Test
    @DisplayName("COMPLETED is terminal: cancelling one is refused, so stock is never returned twice")
    void completedIsNotCancellable() {
        UUID userId = UUID.randomUUID();
        UUID orderId = confirmedOrder(userId);
        jdbc.update("UPDATE orders SET status = 'COMPLETED' WHERE id = ?", orderId);

        assertThatThrownBy(() -> orderService.cancel(orderId, userId))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ErrorCode.ORDER_NOT_CANCELLABLE);
    }

    @Test
    @DisplayName("the CHECK constraint still rejects a status outside the enum")
    void constraintRejectsUnknownStatus() {
        UUID orderId = confirmedOrder(UUID.randomUUID());

        assertThatThrownBy(() -> jdbc.update("UPDATE orders SET status = 'SHIPPED' WHERE id = ?", orderId))
                .hasMessageContaining("ck_orders_status");
    }
}
