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
import vn.techies.ecommerce.order.api.dto.OrderDtos.PaymentResultResponse;
import vn.techies.ecommerce.order.api.dto.OrderDtos.PaymentConfirmationRequest;
import vn.techies.ecommerce.order.client.CatalogClient;
import vn.techies.ecommerce.order.client.IdentityClient;
import vn.techies.ecommerce.order.client.InventoryClient;
import vn.techies.ecommerce.order.domain.FailureCode;
import vn.techies.ecommerce.order.domain.Order;
import vn.techies.ecommerce.order.domain.OrderStatus;
import vn.techies.ecommerce.order.domain.PaymentMethod;
import vn.techies.ecommerce.order.domain.PaymentStatus;
import vn.techies.ecommerce.order.service.payment.PaymentOutcome;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * The second half of the saga: what happens when the app comes back from the payment screen.
 */
@SpringBootTest
class PaymentServiceTest extends AbstractPostgresTest {

    private static final UUID PRODUCT = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");

    @Autowired
    private CheckoutSagaOrchestrator saga;
    @Autowired
    private PaymentService paymentService;
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

    private PaymentResultResponse report(Order order, PaymentOutcome outcome, UUID caller) {
        return paymentService.confirmPayment(order.getId(), caller,
                new PaymentConfirmationRequest(outcome, outcome == PaymentOutcome.SUCCESS ? "TXN-1" : null,
                        outcome == PaymentOutcome.FAILED ? "Card declined" : null));
    }

    @Test
    @DisplayName("a successful payment confirms the order and records the transaction reference")
    void successConfirms() {
        Order order = placeCardOrder();

        PaymentResultResponse paid = report(order, PaymentOutcome.SUCCESS, userId);

        assertThat(paid.order().status()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(paid.order().paymentStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(paid.order().paymentRef()).isEqualTo("TXN-1");
    }

    @Test
    @DisplayName("COMPENSATION: a failed payment gives the stock back and fails the order")
    void failureCompensates() {
        Order order = placeCardOrder();

        PaymentResultResponse failed = report(order, PaymentOutcome.FAILED, userId);

        assertThat(failed.order().status()).isEqualTo(OrderStatus.FAILED);
        assertThat(failed.order().failureCode()).isEqualTo(FailureCode.PAYMENT_FAILED);
        verify(inventoryClient).restore(any());
    }

    @Test
    @DisplayName("a failed payment keeps the cart, so the customer can try again")
    void failureKeepsTheCart() {
        Order order = placeCardOrder();

        report(order, PaymentOutcome.FAILED, userId);

        assertThat(cartService.view(userId).items()).hasSize(1);
    }

    @Test
    @DisplayName("IDEMPOTENT: reporting the same success twice confirms once and does not double-clear")
    void repeatedSuccessIsANoOp() {
        Order order = placeCardOrder();
        report(order, PaymentOutcome.SUCCESS, userId);

        PaymentResultResponse again = report(order, PaymentOutcome.SUCCESS, userId);

        assertThat(again.order().status()).isEqualTo(OrderStatus.CONFIRMED);
        verify(inventoryClient, never()).restore(any());
    }

    @Test
    @DisplayName("IDEMPOTENT: reporting the same failure twice restores stock only once")
    void repeatedFailureRestoresOnce() {
        Order order = placeCardOrder();
        report(order, PaymentOutcome.FAILED, userId);

        PaymentResultResponse again = report(order, PaymentOutcome.FAILED, userId);

        assertThat(again.order().status()).isEqualTo(OrderStatus.FAILED);
        verify(inventoryClient, times(1)).restore(any());
    }

    @Test
    @DisplayName("claiming a paid order failed is refused: that is a cancellation, not a payment")
    void cannotFailAPaidOrder() {
        Order order = placeCardOrder();
        report(order, PaymentOutcome.SUCCESS, userId);

        assertThatThrownBy(() -> report(order, PaymentOutcome.FAILED, userId))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ErrorCode.ORDER_NOT_PAYABLE);
    }

    @Test
    @DisplayName("paying an order whose stock was already given back is refused")
    void cannotPayAFailedOrder() {
        Order order = placeCardOrder();
        report(order, PaymentOutcome.FAILED, userId);

        assertThatThrownBy(() -> report(order, PaymentOutcome.SUCCESS, userId))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ErrorCode.ORDER_NOT_PAYABLE);
    }

    @Test
    @DisplayName("a COD order has nothing to pay now and is refused")
    void codIsNotPayable() {
        cartService.add(userId, new AddCartItemRequest(PRODUCT, 1));
        Order cod = saga.checkout(userId, new CheckoutRequest(UUID.randomUUID(), PaymentMethod.COD, null));

        assertThatThrownBy(() -> report(cod, PaymentOutcome.SUCCESS, userId))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ErrorCode.ORDER_NOT_PAYABLE);
    }

    @Test
    @DisplayName("a failed payment returns the ordered lines to the cart")
    void failureReturnsLinesToCart() {
        Order order = placeCardOrder();
        assertThat(cartService.view(userId).items()).as("checkout emptied it").isEmpty();

        PaymentResultResponse failed = report(order, PaymentOutcome.FAILED, userId);

        assertThat(cartService.view(userId).items()).hasSize(1);
        assertThat(failed.cartRestore().linesReturned()).isEqualTo(1);
        assertThat(failed.cartRestore().unavailable()).isEmpty();
    }

    @Test
    @DisplayName("MERGE AND SUM: what they added while paying is not discarded by the restore")
    void restoreMergesWithTheCurrentCart() {
        Order order = placeCardOrder();          // 1 unit ordered
        cartService.add(userId, new AddCartItemRequest(PRODUCT, 2));  // 2 more added meanwhile

        report(order, PaymentOutcome.FAILED, userId);

        assertThat(cartService.view(userId).items())
                .singleElement()
                .extracting("quantity").isEqualTo(3);
    }

    @Test
    @DisplayName("the restored quantity is capped at stock, since someone else may have bought it")
    void restoreIsCappedAtAvailableStock() {
        Order order = placeCardOrder();
        cartService.add(userId, new AddCartItemRequest(PRODUCT, 2));
        // Only one unit is left by the time the payment fails.
        given(inventoryClient.getStock(any()))
                .willReturn(new InventoryClient.StockResponse(PRODUCT, 1, true));

        PaymentResultResponse failed = report(order, PaymentOutcome.FAILED, userId);

        assertThat(cartService.view(userId).items())
                .as("the cart never shows more than can be bought")
                .singleElement().extracting("quantity").isEqualTo(2);
        assertThat(failed.cartRestore().unavailable()).containsExactly("Sản phẩm");
    }

    @Test
    @DisplayName("a delisted product is not put back, and is named so the app can say so")
    void delistedProductIsNotRestored() {
        Order order = placeCardOrder();
        given(catalogClient.batch(any())).willReturn(List.of(new CatalogClient.ProductSnapshot(
                PRODUCT, "Sản phẩm", new BigDecimal("100000.00"), "t", false)));

        PaymentResultResponse failed = report(order, PaymentOutcome.FAILED, userId);

        assertThat(cartService.view(userId).items()).isEmpty();
        assertThat(failed.cartRestore().unavailable()).containsExactly("Sản phẩm");
    }

    @Test
    @DisplayName("backing out of payment and checking out again does not hold the stock twice")
    void abandonedOrderIsReleasedOnRecheckout() {
        Order first = placeCardOrder();
        // The customer navigates back to the cart without paying. The cart still holds the
        // line, so the Checkout button places a second order for the same goods.
        cartService.add(userId, new AddCartItemRequest(PRODUCT, 1));
        Order second = saga.checkout(userId,
                new CheckoutRequest(UUID.randomUUID(), PaymentMethod.MOCK_CARD, null));

        assertThat(second.getId()).isNotEqualTo(first.getId());
        assertThat(orderService.detail(first.getId(), userId).status())
                .as("the abandoned order must not keep holding stock")
                .isEqualTo(OrderStatus.FAILED);
        verify(inventoryClient).restore(any());
        assertThat(cartService.view(userId).items())
                .as("a superseded order must not refill the cart the new one just emptied")
                .isEmpty();
    }

    @Test
    @DisplayName("one user cannot pay for, and so confirm, another user's order")
    void paymentIsScopedToOwner() {
        Order order = placeCardOrder();

        assertThatThrownBy(() -> report(order, PaymentOutcome.SUCCESS, UUID.randomUUID()))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ErrorCode.FORBIDDEN);

        assertThat(orderService.detail(order.getId(), userId).status()).isEqualTo(OrderStatus.AWAITING_PAYMENT);
    }
}
