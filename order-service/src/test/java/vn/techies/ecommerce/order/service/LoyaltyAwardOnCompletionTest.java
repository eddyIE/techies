package vn.techies.ecommerce.order.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import vn.techies.ecommerce.order.AbstractPostgresTest;
import vn.techies.ecommerce.order.api.dto.CartDtos.AddCartItemRequest;
import vn.techies.ecommerce.order.api.dto.OrderDtos.CheckoutRequest;
import vn.techies.ecommerce.order.client.CatalogClient;
import vn.techies.ecommerce.order.client.IdentityClient;
import vn.techies.ecommerce.order.client.InventoryClient;
import vn.techies.ecommerce.order.client.LoyaltyClient;
import vn.techies.ecommerce.order.domain.Order;
import vn.techies.ecommerce.order.domain.OrderStatus;
import vn.techies.ecommerce.order.domain.PaymentMethod;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

/**
 * Points are credited on the way into COMPLETED and nowhere else. Nothing reaches COMPLETED on
 * its own, so this fires only from the demo status endpoint standing in for the missing actor.
 */
@SpringBootTest
class LoyaltyAwardOnCompletionTest extends AbstractPostgresTest {

    private static final UUID PRODUCT = UUID.fromString("dddddddd-0000-0000-0000-000000000004");
    /** 30.000đ off, no minimum order, so a 100.000đ cart can carry it. */
    private static final String COUPON = "FREESHIP30K";

    @Autowired
    private CheckoutSagaOrchestrator saga;
    @Autowired
    private CartService cartService;
    @Autowired
    private OrderService orderService;

    @MockitoBean
    private IdentityClient identityClient;
    @MockitoBean
    private CatalogClient catalogClient;
    @MockitoBean
    private InventoryClient inventoryClient;
    @MockitoBean
    private LoyaltyClient loyaltyClient;

    @BeforeEach
    void setUp() {
        given(identityClient.getAddress(any(), any())).willReturn(
                new IdentityClient.AddressSnapshot(UUID.randomUUID(), "A", "0901234567",
                        "1 Le Loi", "W", "D", "HCM", true));
        given(catalogClient.batch(any())).willReturn(List.of(
                new CatalogClient.ProductSnapshot(PRODUCT, "Sản phẩm",
                        new BigDecimal("100000.00"), "t", true)));
        given(inventoryClient.deduct(any()))
                .willReturn(new InventoryClient.MovementResponse("r", UUID.randomUUID(), true, false));
        given(loyaltyClient.award(any()))
                .willReturn(new LoyaltyClient.AwardResponse(true, 100, 1, List.of()));
    }

    private Order confirmedOrder(UUID userId, String couponCode) {
        cartService.add(userId, new AddCartItemRequest(PRODUCT, 1));
        Order order = saga.checkout(userId,
                new CheckoutRequest(UUID.randomUUID(), PaymentMethod.COD, null, couponCode));
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        return order;
    }

    private LoyaltyClient.AwardRequest awardSent() {
        ArgumentCaptor<LoyaltyClient.AwardRequest> sent =
                ArgumentCaptor.forClass(LoyaltyClient.AwardRequest.class);
        then(loyaltyClient).should().award(sent.capture());
        return sent.getValue();
    }

    @Test
    @DisplayName("Completing an order credits its points")
    void awardsOnCompletion() {
        UUID userId = UUID.randomUUID();
        Order order = confirmedOrder(userId, null);

        orderService.updateStatus(order.getId(), userId, OrderStatus.COMPLETED);

        then(loyaltyClient).should().award(any());
    }

    @Test
    @DisplayName("The order ref goes along as the idempotency key loyalty dedupes on")
    void sendsTheOrderRef() {
        UUID userId = UUID.randomUUID();
        Order order = confirmedOrder(userId, null);

        orderService.updateStatus(order.getId(), userId, OrderStatus.COMPLETED);

        assertThat(awardSent().orderRef()).isEqualTo(order.getOrderRef());
    }

    @Test
    @DisplayName("The shipping fee earns nothing: delivery is not spend")
    void excludesTheShippingFee() {
        UUID userId = UUID.randomUUID();
        Order order = confirmedOrder(userId, null);
        assertThat(order.getTotal()).isEqualByComparingTo("130000.00");

        orderService.updateStatus(order.getId(), userId, OrderStatus.COMPLETED);

        assertThat(awardSent().amountSpent()).isEqualByComparingTo("100000.00");
    }

    @Test
    @DisplayName("A coupon-discounted order earns on what was actually paid for goods")
    void awardsOnSubtotalMinusDiscount() {
        UUID userId = UUID.randomUUID();
        Order order = confirmedOrder(userId, COUPON);
        assertThat(order.getDiscount()).isEqualByComparingTo("30000.00");

        orderService.updateStatus(order.getId(), userId, OrderStatus.COMPLETED);

        assertThat(awardSent().amountSpent()).isEqualByComparingTo("70000.00");
    }

    @Test
    @DisplayName("Confirming an order credits nothing: only COMPLETED earns")
    void awardsNothingOnConfirmed() {
        UUID userId = UUID.randomUUID();
        Order order = confirmedOrder(userId, null);

        orderService.updateStatus(order.getId(), userId, OrderStatus.CONFIRMED);

        then(loyaltyClient).should(never()).award(any());
    }

    @Test
    @DisplayName("Cancelling an order credits nothing")
    void awardsNothingOnCancelled() {
        UUID userId = UUID.randomUUID();
        Order order = confirmedOrder(userId, null);

        orderService.updateStatus(order.getId(), userId, OrderStatus.CANCELLED);

        then(loyaltyClient).should(never()).award(any());
    }

    @Test
    @DisplayName("Re-sending COMPLETED credits nothing, since the transition already happened")
    void awardsOnceAcrossARepeatedCompletion() {
        UUID userId = UUID.randomUUID();
        Order order = confirmedOrder(userId, null);
        orderService.updateStatus(order.getId(), userId, OrderStatus.COMPLETED);

        orderService.updateStatus(order.getId(), userId, OrderStatus.COMPLETED);

        then(loyaltyClient).should(times(1)).award(any());
    }

    @Test
    @DisplayName("A loyalty outage leaves the order completed: points must never block the lifecycle")
    void completesDespiteALoyaltyOutage() {
        UUID userId = UUID.randomUUID();
        Order order = confirmedOrder(userId, null);
        willThrow(new IllegalStateException("loyalty down")).given(loyaltyClient).award(any());

        assertThat(orderService.updateStatus(order.getId(), userId, OrderStatus.COMPLETED).status())
                .isEqualTo(OrderStatus.COMPLETED);
    }
}
