package vn.techies.ecommerce.order.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import vn.techies.ecommerce.common.error.ApiException;
import vn.techies.ecommerce.common.error.ErrorCode;
import vn.techies.ecommerce.order.AbstractPostgresTest;
import vn.techies.ecommerce.order.api.dto.CartDtos.AddCartItemRequest;
import vn.techies.ecommerce.order.api.dto.OrderDtos.CheckoutRequest;
import vn.techies.ecommerce.order.api.dto.OrderDtos.PaymentConfirmationRequest;
import vn.techies.ecommerce.order.client.CatalogClient;
import vn.techies.ecommerce.order.client.FeignErrors;
import vn.techies.ecommerce.order.client.IdentityClient;
import vn.techies.ecommerce.order.client.InventoryClient;
import vn.techies.ecommerce.order.client.LoyaltyClient;
import vn.techies.ecommerce.order.domain.Order;
import vn.techies.ecommerce.order.domain.OrderStatus;
import vn.techies.ecommerce.order.domain.PaymentMethod;
import vn.techies.ecommerce.order.repository.OrderRepository;
import vn.techies.ecommerce.order.service.payment.PaymentOutcome;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;

/**
 * A tier voucher at checkout. Coupons are resolved locally first, so an ordinary coupon order
 * never depends on loyalty being up; only an unrecognised code is offered to loyalty.
 */
@SpringBootTest
class LoyaltyVoucherCheckoutTest extends AbstractPostgresTest {

    private static final UUID PRODUCT = UUID.fromString("cccccccc-0000-0000-0000-000000000001");
    private static final String VOUCHER = "TIER1-ABCD2345";
    /** Seeded by V8: 30.000đ off, no minimum. */
    private static final String COUPON = "FREESHIP30K";

    @Autowired
    private CheckoutSagaOrchestrator saga;
    @Autowired
    private CartService cartService;
    @Autowired
    private OrderService orderService;
    @Autowired
    private PaymentService paymentService;
    @Autowired
    private OrderRepository orders;

    @MockitoBean
    private IdentityClient identityClient;
    @MockitoBean
    private CatalogClient catalogClient;
    @MockitoBean
    private InventoryClient inventoryClient;
    @MockitoBean
    private LoyaltyClient loyaltyClient;

    private UUID userId;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        given(identityClient.getAddress(any(), any())).willReturn(
                new IdentityClient.AddressSnapshot(UUID.randomUUID(), "A", "0901234567",
                        "1 Le Loi", "W", "D", "HCM", true));
        given(catalogClient.batch(any())).willReturn(List.of(
                new CatalogClient.ProductSnapshot(PRODUCT, "Sản phẩm",
                        new BigDecimal("100000.00"), "t", true)));
        given(inventoryClient.deduct(any()))
                .willReturn(new InventoryClient.MovementResponse("r", UUID.randomUUID(), true, false));
        given(loyaltyClient.consume(eq(VOUCHER), any()))
                .willReturn(new LoyaltyClient.ConsumeResponse(VOUCHER, new BigDecimal("10000")));
        given(loyaltyClient.release(any(), any()))
                .willReturn(new LoyaltyClient.ReleaseResponse(VOUCHER, true));
    }

    private Order checkout(String code, PaymentMethod method) {
        cartService.add(userId, new AddCartItemRequest(PRODUCT, 1));
        return saga.checkout(userId, new CheckoutRequest(UUID.randomUUID(), method, null, code));
    }

    private Order checkout(String code) {
        return checkout(code, PaymentMethod.COD);
    }

    @Test
    @DisplayName("A coupon is resolved locally, so loyalty is never asked about it")
    void resolvesACouponWithoutLoyalty() {
        checkout(COUPON);

        then(loyaltyClient).should(never()).consume(any(), any());
    }

    @Test
    @DisplayName("An unrecognised code is offered to loyalty")
    void offersAnUnknownCodeToLoyalty() {
        checkout(VOUCHER);

        then(loyaltyClient).should().consume(eq(VOUCHER), any());
    }

    @Test
    @DisplayName("The voucher's discount is snapshotted onto the order, like a coupon's")
    void snapshotsTheVoucherDiscount() {
        assertThat(checkout(VOUCHER).getDiscount()).isEqualByComparingTo("10000");
    }

    @Test
    @DisplayName("The order keeps the voucher code, so the claimed discount is traceable")
    void recordsTheVoucherCode() {
        assertThat(checkout(VOUCHER).getCouponCode()).isEqualTo(VOUCHER);
    }

    @Test
    @DisplayName("The order records that the discount came from loyalty, not from a coupon")
    void recordsTheDiscountSource() {
        assertThat(checkout(VOUCHER).usedLoyaltyVoucher()).isTrue();
    }

    @Test
    @DisplayName("A coupon order is not marked as voucher-backed, so cancelling it releases nothing")
    void doesNotMarkACouponOrder() {
        assertThat(checkout(COUPON).usedLoyaltyVoucher()).isFalse();
    }

    @Test
    @DisplayName("Consume cites the order ref the order will carry, which release later matches on")
    void consumesAgainstTheOrdersOwnRef() {
        Order order = checkout(VOUCHER);

        ArgumentCaptor<LoyaltyClient.ConsumeRequest> sent =
                ArgumentCaptor.forClass(LoyaltyClient.ConsumeRequest.class);
        then(loyaltyClient).should().consume(eq(VOUCHER), sent.capture());
        assertThat(sent.getValue().orderRef()).isEqualTo(order.getOrderRef());
    }

    @Test
    @DisplayName("The percentage applies to the goods, so the subtotal is what loyalty is told")
    void consumesAgainstTheSubtotal() {
        checkout(VOUCHER);

        ArgumentCaptor<LoyaltyClient.ConsumeRequest> sent =
                ArgumentCaptor.forClass(LoyaltyClient.ConsumeRequest.class);
        then(loyaltyClient).should().consume(eq(VOUCHER), sent.capture());
        assertThat(sent.getValue().subtotal()).isEqualByComparingTo("100000.00");
    }

    @Test
    @DisplayName("A code neither system knows is refused as an unknown coupon")
    void refusesACodeNobodyKnows() {
        willThrow(FeignErrors.status(404)).given(loyaltyClient).consume(any(), any());

        assertThatThrownBy(() -> checkout("NOPE"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ErrorCode.COUPON_NOT_FOUND);
    }

    @Test
    @DisplayName("A voucher another cart is holding is refused as unusable, not as missing")
    void refusesAVoucherAlreadySpent() {
        willThrow(FeignErrors.status(409)).given(loyaltyClient).consume(any(), any());

        assertThatThrownBy(() -> checkout(VOUCHER))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ErrorCode.COUPON_NOT_APPLICABLE);
    }

    @Test
    @DisplayName("A refused voucher leaves no order in history, since it is checked before the row")
    void writesNoOrderWhenTheVoucherIsRefused() {
        willThrow(FeignErrors.status(409)).given(loyaltyClient).consume(any(), any());

        assertThatThrownBy(() -> checkout(VOUCHER)).isInstanceOf(ApiException.class);

        assertThat(orders.findAll().stream().noneMatch(o -> o.getUserId().equals(userId))).isTrue();
    }

    @Test
    @DisplayName("A loyalty outage says to try again rather than denying a real voucher exists")
    void reportsAnOutageRatherThanDenyingTheCode() {
        willThrow(new IllegalStateException("loyalty down")).given(loyaltyClient).consume(any(), any());

        assertThatThrownBy(() -> checkout(VOUCHER))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ErrorCode.SERVICE_UNAVAILABLE);
    }

    @Test
    @DisplayName("A failed stock deduction gives the voucher back")
    void releasesTheVoucherWhenStockRunsOut() {
        willThrow(FeignErrors.status(409)).given(inventoryClient).deduct(any());

        checkout(VOUCHER);

        then(loyaltyClient).should().release(eq(VOUCHER), any());
    }

    @Test
    @DisplayName("A declined payment gives the voucher back, unconsumed for the next attempt")
    void releasesTheVoucherWhenPaymentFails() {
        Order order = checkout(VOUCHER, PaymentMethod.MOCK_CARD);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.AWAITING_PAYMENT);

        paymentService.confirmPayment(order.getId(), userId,
                new PaymentConfirmationRequest(PaymentOutcome.FAILED, null, "Card declined"));

        then(loyaltyClient).should().release(eq(VOUCHER), any());
    }

    @Test
    @DisplayName("Cancelling a confirmed order gives the voucher back: nothing went wrong there")
    void releasesTheVoucherOnCancellation() {
        Order order = checkout(VOUCHER);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);

        orderService.cancel(order.getId(), userId);

        then(loyaltyClient).should().release(eq(VOUCHER), any());
    }

    @Test
    @DisplayName("Release cites the order that held the voucher, not some other order")
    void releasesAgainstTheHoldingOrder() {
        Order order = checkout(VOUCHER);

        orderService.cancel(order.getId(), userId);

        ArgumentCaptor<LoyaltyClient.ReleaseRequest> sent =
                ArgumentCaptor.forClass(LoyaltyClient.ReleaseRequest.class);
        then(loyaltyClient).should().release(eq(VOUCHER), sent.capture());
        assertThat(sent.getValue().orderRef()).isEqualTo(order.getOrderRef());
    }

    @Test
    @DisplayName("Cancelling a coupon order releases nothing: a coupon is reusable")
    void releasesNothingForACouponOrder() {
        Order order = checkout(COUPON);

        orderService.cancel(order.getId(), userId);

        then(loyaltyClient).should(never()).release(any(), any());
    }

    @Test
    @DisplayName("A release that itself fails still cancels the order, with the code logged as stranded")
    void cancelsEvenWhenReleaseFails() {
        Order order = checkout(VOUCHER);
        willThrow(FeignErrors.status(409)).given(loyaltyClient).release(any(), any());

        assertThat(orderService.cancel(order.getId(), userId).status())
                .isEqualTo(OrderStatus.CANCELLED);
    }
}
