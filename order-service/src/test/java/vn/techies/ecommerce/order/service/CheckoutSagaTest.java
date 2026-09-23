package vn.techies.ecommerce.order.service;

import feign.FeignException;
import feign.Request;
import feign.RequestTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import vn.techies.ecommerce.common.error.ApiException;
import vn.techies.ecommerce.common.error.ErrorCode;
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
import vn.techies.ecommerce.order.domain.PaymentStatus;
import vn.techies.ecommerce.order.domain.SagaStep;
import vn.techies.ecommerce.order.domain.SagaStepStatus;
import vn.techies.ecommerce.order.service.payment.PaymentSimulation;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Exercises every branch of the checkout saga against a real database, with the three remote
 * services mocked. These are the cases a lecturer will ask about.
 */
@SpringBootTest
class CheckoutSagaTest extends AbstractPostgresTest {

    private static final UUID PRODUCT_A = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final UUID PRODUCT_B = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");
    private static final BigDecimal PRICE_A = new BigDecimal("150000.00");
    private static final BigDecimal PRICE_B = new BigDecimal("250000.00");

    @Autowired
    private CheckoutSagaOrchestrator saga;
    @Autowired
    private CartService cartService;
    @Autowired
    private OrderService orderService;
    @Autowired
    private SagaRecorder sagaRecorder;

    @MockitoBean
    private IdentityClient identityClient;
    @MockitoBean
    private CatalogClient catalogClient;
    @MockitoBean
    private InventoryClient inventoryClient;

    private UUID userId;
    private UUID addressId;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        addressId = UUID.randomUUID();

        given(identityClient.getAddress(any(), any())).willReturn(
                new IdentityClient.AddressSnapshot(addressId, "Nguyen Van A", "0901234567",
                        "12 Nguyen Hue", "Ben Nghe", "Quan 1", "Ho Chi Minh", true));

        given(catalogClient.batch(any())).willReturn(List.of(
                new CatalogClient.ProductSnapshot(PRODUCT_A, "Sản phẩm A", PRICE_A, "thumb-a", true),
                new CatalogClient.ProductSnapshot(PRODUCT_B, "Sản phẩm B", PRICE_B, "thumb-b", true)));

        given(inventoryClient.deduct(any()))
                .willReturn(new InventoryClient.MovementResponse("ref", UUID.randomUUID(), true, false));
        given(inventoryClient.restore(any()))
                .willReturn(new InventoryClient.MovementResponse("ref", UUID.randomUUID(), false, true));
        given(inventoryClient.getStock(any()))
                .willReturn(new InventoryClient.StockResponse(PRODUCT_A, 50, true));
    }

    private void fillCart() {
        cartService.add(userId, new AddCartItemRequest(PRODUCT_A, 2));
        cartService.add(userId, new AddCartItemRequest(PRODUCT_B, 1));
    }

    private CheckoutRequest checkoutWith(PaymentSimulation simulation) {
        return new CheckoutRequest(addressId, PaymentMethod.MOCK_CARD, simulation);
    }

    private List<SagaStep> trail(Order order) {
        return sagaRecorder.trailFor(order.getId());
    }

    private static FeignException conflict(String body) {
        return FeignException.errorStatus("deduct", feign.Response.builder()
                .status(409)
                .reason("Conflict")
                .request(Request.create(Request.HttpMethod.POST, "/stock/deduct", Collections.emptyMap(),
                        null, StandardCharsets.UTF_8, new RequestTemplate()))
                .headers(Collections.emptyMap())
                .body(body, StandardCharsets.UTF_8)
                .build());
    }

    @Test
    @DisplayName("happy path: order CONFIRMED, stock deducted, cart cleared, full saga trail")
    void happyCheckout() {
        fillCart();

        Order order = saga.checkout(userId, checkoutWith(PaymentSimulation.SUCCESS));

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(order.getPaymentStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(order.getFailureCode()).isNull();
        assertThat(order.getOrderRef()).matches("ORD-\\d{8}-\\d{4}");

        // 2 x 150000 + 1 x 250000 = 550000, which clears the 500000 free-shipping threshold.
        assertThat(order.getSubtotal()).isEqualByComparingTo("550000.00");
        assertThat(order.getShippingFee()).isEqualByComparingTo("0.00");
        assertThat(order.getTotal()).isEqualByComparingTo("550000.00");

        verify(inventoryClient).deduct(any());
        verify(inventoryClient, never()).restore(any());

        assertThat(cartService.view(userId).items()).as("cart cleared on success").isEmpty();

        List<SagaStep> steps = trail(order);
        assertThat(steps).extracting(SagaStep::getStepName)
                .contains("1-LOAD_CART", "2-SNAPSHOT_ADDRESS", "3-SNAPSHOT_PRODUCTS",
                        "4-PERSIST_ORDER", "5-DEDUCT_STOCK", "6-CHARGE_PAYMENT", "7-CONFIRM_ORDER");
        assertThat(steps).noneMatch(s -> s.getStatus() == SagaStepStatus.FAILED);
    }

    @Test
    @DisplayName("COMPENSATION PROOF: declined payment restores stock, keeps the cart, marks COMPENSATED")
    void declinedPaymentCompensates() {
        fillCart();

        Order order = saga.checkout(userId, checkoutWith(PaymentSimulation.DECLINED));

        assertThat(order.getStatus()).isEqualTo(OrderStatus.FAILED);
        assertThat(order.getFailureCode()).isEqualTo(FailureCode.PAYMENT_FAILED);
        assertThat(order.getPaymentStatus()).isEqualTo(PaymentStatus.DECLINED);

        // The compensating transaction ran.
        verify(inventoryClient).deduct(any());
        verify(inventoryClient).restore(any());

        // Order Flow loops Payment Result back to Checkout, so the cart must survive.
        assertThat(cartService.view(userId).items()).as("cart kept for retry").hasSize(2);

        assertThat(trail(order))
                .filteredOn(s -> s.getStepName().equals("5-DEDUCT_STOCK"))
                .extracting(SagaStep::getStatus)
                .contains(SagaStepStatus.COMPENSATED);
    }

    @Test
    @DisplayName("a simulated payment timeout is treated as a decline and compensates identically")
    void timeoutCompensatesLikeDecline() {
        fillCart();

        Order order = saga.checkout(userId, checkoutWith(PaymentSimulation.TIMEOUT));

        assertThat(order.getStatus()).isEqualTo(OrderStatus.FAILED);
        assertThat(order.getFailureCode()).isEqualTo(FailureCode.PAYMENT_FAILED);
        verify(inventoryClient).restore(any());
    }

    @Test
    @DisplayName("insufficient stock fails the order and never attempts payment")
    void outOfStockSkipsPayment() {
        fillCart();
        willThrow(conflict("{\"code\":\"INSUFFICIENT_STOCK\"}")).given(inventoryClient).deduct(any());

        Order order = saga.checkout(userId, checkoutWith(PaymentSimulation.SUCCESS));

        assertThat(order.getStatus()).isEqualTo(OrderStatus.FAILED);
        assertThat(order.getFailureCode()).isEqualTo(FailureCode.OUT_OF_STOCK);
        assertThat(order.getPaymentStatus()).isEqualTo(PaymentStatus.PENDING);

        // Nothing was deducted, so there is nothing to compensate.
        verify(inventoryClient, never()).restore(any());

        assertThat(trail(order))
                .as("no payment step is ever recorded")
                .noneMatch(s -> s.getStepName().equals("6-CHARGE_PAYMENT"));
    }

    @Test
    @DisplayName("an unreachable inventory service fails the order as SERVICE_UNAVAILABLE")
    void inventoryDownFailsCleanly() {
        fillCart();
        willThrow(new RuntimeException("connection refused")).given(inventoryClient).deduct(any());

        Order order = saga.checkout(userId, checkoutWith(PaymentSimulation.SUCCESS));

        assertThat(order.getStatus()).isEqualTo(OrderStatus.FAILED);
        assertThat(order.getFailureCode()).isEqualTo(FailureCode.SERVICE_UNAVAILABLE);
        verify(inventoryClient, never()).restore(any());
    }

    @Test
    @DisplayName("checking out an empty cart creates no order at all")
    void emptyCartCreatesNoOrder() {
        assertThatThrownBy(() -> saga.checkout(userId, checkoutWith(PaymentSimulation.SUCCESS)))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ErrorCode.EMPTY_CART);

        assertThat(orderService.list(userId, null, 0, 20).content()).isEmpty();
    }

    @Test
    @DisplayName("an address belonging to someone else creates no order")
    void foreignAddressCreatesNoOrder() {
        fillCart();
        willThrow(new RuntimeException("404")).given(identityClient).getAddress(any(), any());

        assertThatThrownBy(() -> saga.checkout(userId, checkoutWith(PaymentSimulation.SUCCESS)))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ErrorCode.ADDRESS_NOT_FOUND);

        assertThat(orderService.list(userId, null, 0, 20).content()).isEmpty();
        verify(inventoryClient, never()).deduct(any());
    }

    @Test
    @DisplayName("a delisted product blocks checkout before any stock moves")
    void inactiveProductBlocksCheckout() {
        fillCart();
        given(catalogClient.batch(any())).willReturn(List.of(
                new CatalogClient.ProductSnapshot(PRODUCT_A, "Sản phẩm A", PRICE_A, "thumb-a", true),
                new CatalogClient.ProductSnapshot(PRODUCT_B, "Sản phẩm B", PRICE_B, "thumb-b", false)));

        assertThatThrownBy(() -> saga.checkout(userId, checkoutWith(PaymentSimulation.SUCCESS)))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ErrorCode.PRODUCT_UNAVAILABLE);

        verify(inventoryClient, never()).deduct(any());
    }

    @Test
    @DisplayName("COD always succeeds without a card simulation")
    void codAlwaysSucceeds() {
        fillCart();

        Order order = saga.checkout(userId, new CheckoutRequest(addressId, PaymentMethod.COD, null));

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(order.getPaymentStatus()).isEqualTo(PaymentStatus.PAID);
    }

    @Test
    @DisplayName("a later catalog price change does not alter an existing order")
    void orderPricesAreSnapshots() {
        fillCart();
        Order order = saga.checkout(userId, checkoutWith(PaymentSimulation.SUCCESS));
        BigDecimal originalTotal = order.getTotal();

        // The catalog doubles its prices afterwards.
        given(catalogClient.batch(any())).willReturn(List.of(
                new CatalogClient.ProductSnapshot(PRODUCT_A, "Sản phẩm A", PRICE_A.multiply(BigDecimal.TWO), "t", true),
                new CatalogClient.ProductSnapshot(PRODUCT_B, "Sản phẩm B", PRICE_B.multiply(BigDecimal.TWO), "t", true)));

        var reloaded = orderService.detail(order.getId(), userId);

        assertThat(reloaded.total()).isEqualByComparingTo(originalTotal);
        assertThat(reloaded.items()).extracting("unitPrice")
                .containsExactlyInAnyOrder(PRICE_A, PRICE_B);
    }

    @Test
    @DisplayName("shipping is charged below the free threshold and waived above it")
    void shippingPolicyApplies() {
        cartService.add(userId, new AddCartItemRequest(PRODUCT_A, 1)); // 150000 < 500000

        Order order = saga.checkout(userId, checkoutWith(PaymentSimulation.SUCCESS));

        assertThat(order.getSubtotal()).isEqualByComparingTo("150000.00");
        assertThat(order.getShippingFee()).isEqualByComparingTo("30000");
        assertThat(order.getTotal()).isEqualByComparingTo("180000.00");
    }

    @Test
    @DisplayName("cancelling a confirmed order restores stock and refunds")
    void cancelRestoresStock() {
        fillCart();
        Order order = saga.checkout(userId, checkoutWith(PaymentSimulation.SUCCESS));

        var cancelled = orderService.cancel(order.getId(), userId);

        assertThat(cancelled.status()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(cancelled.paymentStatus()).isEqualTo(PaymentStatus.REFUNDED);
        verify(inventoryClient).restore(any());
    }

    @Test
    @DisplayName("cancelling a FAILED order is refused, so stock is never restored twice")
    void cannotCancelFailedOrder() {
        fillCart();
        Order failed = saga.checkout(userId, checkoutWith(PaymentSimulation.DECLINED));

        assertThatThrownBy(() -> orderService.cancel(failed.getId(), userId))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ErrorCode.ORDER_NOT_CANCELLABLE);
    }

    @Test
    @DisplayName("cancelling twice is refused the second time")
    void cannotCancelTwice() {
        fillCart();
        Order order = saga.checkout(userId, checkoutWith(PaymentSimulation.SUCCESS));
        orderService.cancel(order.getId(), userId);

        assertThatThrownBy(() -> orderService.cancel(order.getId(), userId))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ErrorCode.ORDER_NOT_CANCELLABLE);
    }

    @Test
    @DisplayName("one user cannot read or cancel another user's order")
    void ordersAreScopedToOwner() {
        fillCart();
        Order order = saga.checkout(userId, checkoutWith(PaymentSimulation.SUCCESS));
        UUID intruder = UUID.randomUUID();

        assertThatThrownBy(() -> orderService.detail(order.getId(), intruder))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ErrorCode.FORBIDDEN);

        assertThatThrownBy(() -> orderService.cancel(order.getId(), intruder))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }
}
