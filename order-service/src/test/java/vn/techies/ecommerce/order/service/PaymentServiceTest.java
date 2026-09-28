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
import vn.techies.ecommerce.order.api.dto.OrderDtos.OrderResponse;
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

    private OrderResponse report(Order order, PaymentOutcome outcome, UUID caller) {
        return paymentService.confirmPayment(order.getId(), caller,
                new PaymentConfirmationRequest(outcome, outcome == PaymentOutcome.SUCCESS ? "TXN-1" : null,
                        outcome == PaymentOutcome.FAILED ? "Card declined" : null));
    }

    @Test
    @DisplayName("a successful payment confirms the order and records the transaction reference")
    void successConfirms() {
        Order order = placeCardOrder();

        OrderResponse paid = report(order, PaymentOutcome.SUCCESS, userId);

        assertThat(paid.status()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(paid.paymentStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(paid.paymentRef()).isEqualTo("TXN-1");
    }

    @Test
    @DisplayName("COMPENSATION: a failed payment gives the stock back and fails the order")
    void failureCompensates() {
        Order order = placeCardOrder();

        OrderResponse failed = report(order, PaymentOutcome.FAILED, userId);

        assertThat(failed.status()).isEqualTo(OrderStatus.FAILED);
        assertThat(failed.failureCode()).isEqualTo(FailureCode.PAYMENT_FAILED);
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

        OrderResponse again = report(order, PaymentOutcome.SUCCESS, userId);

        assertThat(again.status()).isEqualTo(OrderStatus.CONFIRMED);
        verify(inventoryClient, never()).restore(any());
    }

    @Test
    @DisplayName("IDEMPOTENT: reporting the same failure twice restores stock only once")
    void repeatedFailureRestoresOnce() {
        Order order = placeCardOrder();
        report(order, PaymentOutcome.FAILED, userId);

        OrderResponse again = report(order, PaymentOutcome.FAILED, userId);

        assertThat(again.status()).isEqualTo(OrderStatus.FAILED);
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
