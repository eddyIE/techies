package vn.techies.ecommerce.order.service;

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
import vn.techies.ecommerce.order.client.FeignErrors;
import vn.techies.ecommerce.order.client.InventoryClient;
import vn.techies.ecommerce.order.client.LoyaltyClient;
import vn.techies.ecommerce.order.domain.Order;
import vn.techies.ecommerce.order.domain.PaymentMethod;

import java.math.BigDecimal;
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
 * Fixed-amount coupons at checkout. The seeded codes come from migration V8.
 */
@SpringBootTest
class CouponCheckoutTest extends AbstractPostgresTest {

    private static final UUID PRODUCT = UUID.fromString("cccccccc-0000-0000-0000-000000000001");
    /** 6 x 100,000 = 600,000, which clears TECHIES50K's 500,000 floor and the free-shipping one. */
    private static final int QUANTITY = 6;

    @Autowired
    private CheckoutSagaOrchestrator saga;
    @Autowired
    private CartService cartService;

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
        // A code that is not a coupon now falls through to loyalty, which does not know it
        // either. Without this, these tests would depend on loyalty-service being reachable.
        willThrow(FeignErrors.status(404)).given(loyaltyClient).consume(any(), any());
        given(catalogClient.batch(any())).willReturn(List.of(new CatalogClient.ProductSnapshot(
                PRODUCT, "Sản phẩm", new BigDecimal("100000.00"), "t", true)));
        given(inventoryClient.deduct(any()))
                .willReturn(new InventoryClient.MovementResponse("r", UUID.randomUUID(), true, false));
    }

    private Order checkout(String couponCode) {
        cartService.add(userId, new AddCartItemRequest(PRODUCT, QUANTITY));
        return saga.checkout(userId,
                new CheckoutRequest(UUID.randomUUID(), PaymentMethod.COD, null, couponCode));
    }

    @Test
    @DisplayName("no coupon means no discount, and the total is subtotal plus shipping")
    void withoutACoupon() {
        Order order = checkout(null);

        assertThat(order.getCouponCode()).isNull();
        assertThat(order.getDiscount()).isEqualByComparingTo("0");
        assertThat(order.getTotal()).isEqualByComparingTo("600000.00");
    }

    @Test
    @DisplayName("a valid coupon comes off the total and is snapshotted onto the order")
    void appliesAFixedDiscount() {
        Order order = checkout("TECHIES50K");

        assertThat(order.getCouponCode()).isEqualTo("TECHIES50K");
        assertThat(order.getDiscount()).isEqualByComparingTo("50000.00");
        // 600,000 subtotal, free shipping above 500,000, less 50,000.
        assertThat(order.getTotal()).isEqualByComparingTo("550000.00");
    }

    @Test
    @DisplayName("the code is case-insensitive: customers type it by hand")
    void codeIsCaseInsensitive() {
        assertThat(checkout("techies50k").getDiscount()).isEqualByComparingTo("50000.00");
    }

    @Test
    @DisplayName("a coupon below its minimum order value is refused before any order exists")
    void refusesBelowMinimumOrderValue() {
        // 500,000 discount needs a 10,000,000 order; this one is 600,000.
        assertThatThrownBy(() -> checkout("TECHIES500K"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ErrorCode.COUPON_NOT_APPLICABLE);

        // Nothing was taken from stock: the coupon is checked before the order is created.
        verify(inventoryClient, never()).deduct(any());
    }

    @Test
    @DisplayName("an expired coupon is refused")
    void refusesExpired() {
        assertThatThrownBy(() -> checkout("EXPIRED100K"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ErrorCode.COUPON_NOT_APPLICABLE);
    }

    @Test
    @DisplayName("a deactivated coupon is refused")
    void refusesInactive() {
        assertThatThrownBy(() -> checkout("PAUSED200K"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ErrorCode.COUPON_NOT_APPLICABLE);
    }

    @Test
    @DisplayName("an unknown code is a 404, not a silently ignored discount")
    void refusesUnknownCode() {
        assertThatThrownBy(() -> checkout("NOPE"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ErrorCode.COUPON_NOT_FOUND);
    }

    @Test
    @DisplayName("a coupon never exceeds the subtotal, so an order can never go negative")
    void discountIsCappedAtTheSubtotal() {
        // One unit is 100,000; FREESHIP30K has no minimum, so it applies to a small order.
        cartService.add(userId, new AddCartItemRequest(PRODUCT, 1));
        Order order = saga.checkout(userId,
                new CheckoutRequest(UUID.randomUUID(), PaymentMethod.COD, null, "FREESHIP30K"));

        // 100,000 subtotal + 30,000 shipping (below the free threshold) - 30,000 discount.
        assertThat(order.getDiscount()).isEqualByComparingTo("30000.00");
        assertThat(order.getTotal()).isEqualByComparingTo("100000.00");
        assertThat(order.getTotal()).isPositive();
    }
}
