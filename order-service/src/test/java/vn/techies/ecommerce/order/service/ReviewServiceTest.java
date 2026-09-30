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
import vn.techies.ecommerce.order.api.dto.OrderDtos.ReviewEntry;
import vn.techies.ecommerce.order.api.dto.OrderDtos.WriteReviewsRequest;
import vn.techies.ecommerce.order.client.CatalogClient;
import vn.techies.ecommerce.order.client.IdentityClient;
import vn.techies.ecommerce.order.client.InventoryClient;
import vn.techies.ecommerce.order.domain.Order;
import vn.techies.ecommerce.order.domain.PaymentMethod;
import vn.techies.ecommerce.order.service.payment.PaymentOutcome;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

/**
 * Reviews are only earned by buying, and are keyed on the purchase rather than the product.
 */
@SpringBootTest
class ReviewServiceTest extends AbstractPostgresTest {

    @Autowired
    private CheckoutSagaOrchestrator saga;
    @Autowired
    private PaymentService paymentService;
    @Autowired
    private ReviewService reviewService;
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
    /**
     * A fresh product per test. These service calls commit, so a shared id would let one
     * test's reviews count towards another's average -- which is exactly what happened.
     */
    private UUID product;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        product = UUID.randomUUID();
        given(identityClient.getAddress(any(), any())).willReturn(
                new IdentityClient.AddressSnapshot(UUID.randomUUID(), "Nguyễn Văn A", "0901234567",
                        "1 Le Loi", "W", "D", "HCM", true));
        given(catalogClient.batch(any())).willReturn(List.of(new CatalogClient.ProductSnapshot(
                product, "Sản phẩm", new BigDecimal("100000.00"), "t", true)));
        given(inventoryClient.deduct(any()))
                .willReturn(new InventoryClient.MovementResponse("r", UUID.randomUUID(), true, false));
        given(inventoryClient.restore(any()))
                .willReturn(new InventoryClient.MovementResponse("r", UUID.randomUUID(), false, true));
    }

    /** COD confirms inline, so this is the shortest route to a settled, reviewable order. */
    private Order paidOrder() {
        cartService.add(userId, new AddCartItemRequest(product, 1));
        return saga.checkout(userId,
                new CheckoutRequest(UUID.randomUUID(), PaymentMethod.COD, null, null));
    }

    private Order awaitingOrder() {
        cartService.add(userId, new AddCartItemRequest(product, 1));
        return saga.checkout(userId,
                new CheckoutRequest(UUID.randomUUID(), PaymentMethod.MOCK_CARD, null, null));
    }

    private UUID firstLineId(Order order) {
        return orderService.detail(order.getId(), userId).items().get(0).id();
    }

    @Test
    @DisplayName("a purchased product can be reviewed, and the order then reads as reviewed")
    void reviewAPurchase() {
        Order order = paidOrder();

        OrderResponse after = reviewService.write(order.getId(), userId, new WriteReviewsRequest(
                List.of(new ReviewEntry(firstLineId(order), 5, "Rất tốt"))));

        assertThat(after.items().get(0).reviewed()).isTrue();
    }

    @Test
    @DisplayName("the review carries the order's recipient name, so identity is never called")
    void reviewUsesTheOrderRecipientName() {
        Order order = paidOrder();
        reviewService.write(order.getId(), userId, new WriteReviewsRequest(
                List.of(new ReviewEntry(firstLineId(order), 4, "Ổn"))));

        assertThat(reviewService.forProduct(product, 0, 10).content().get(0).authorName())
                .isEqualTo("Nguyễn Văn A");
    }

    @Test
    @DisplayName("the rating summary averages every review of the product")
    void averagesRatings() {
        Order a = paidOrder();
        reviewService.write(a.getId(), userId, new WriteReviewsRequest(
                List.of(new ReviewEntry(firstLineId(a), 5, null))));
        Order b = paidOrder();
        reviewService.write(b.getId(), userId, new WriteReviewsRequest(
                List.of(new ReviewEntry(firstLineId(b), 4, null))));

        assertThat(reviewService.forProduct(product, 0, 10).averageRating()).isEqualTo(4.5);
    }

    @Test
    @DisplayName("the same line cannot be reviewed twice")
    void oneReviewPerLine() {
        Order order = paidOrder();
        UUID line = firstLineId(order);
        reviewService.write(order.getId(), userId, new WriteReviewsRequest(
                List.of(new ReviewEntry(line, 5, null))));

        assertThatThrownBy(() -> reviewService.write(order.getId(), userId,
                new WriteReviewsRequest(List.of(new ReviewEntry(line, 1, null)))))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ErrorCode.ALREADY_REVIEWED);
    }

    @Test
    @DisplayName("BUYING TWICE EARNS TWO REVIEWS: a second order of the same product is reviewable")
    void secondPurchaseIsReviewableAgain() {
        Order first = paidOrder();
        reviewService.write(first.getId(), userId, new WriteReviewsRequest(
                List.of(new ReviewEntry(firstLineId(first), 5, "Mua lần đầu"))));

        Order second = paidOrder();
        OrderResponse after = reviewService.write(second.getId(), userId, new WriteReviewsRequest(
                List.of(new ReviewEntry(firstLineId(second), 4, "Mua lần hai"))));

        assertThat(after.items().get(0).reviewed()).isTrue();
        assertThat(reviewService.forProduct(product, 0, 10).total()).isEqualTo(2);
    }

    @Test
    @DisplayName("an unpaid order cannot be reviewed: the customer has not received it")
    void cannotReviewAnUnpaidOrder() {
        Order order = awaitingOrder();
        UUID line = firstLineId(order);

        assertThatThrownBy(() -> reviewService.write(order.getId(), userId,
                new WriteReviewsRequest(List.of(new ReviewEntry(line, 5, null)))))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ErrorCode.ORDER_NOT_REVIEWABLE);
    }

    @Test
    @DisplayName("a failed order cannot be reviewed either")
    void cannotReviewAFailedOrder() {
        Order order = awaitingOrder();
        UUID line = firstLineId(order);
        paymentService.confirmPayment(order.getId(), userId,
                new PaymentConfirmationRequest(PaymentOutcome.FAILED, null, "declined"));

        assertThatThrownBy(() -> reviewService.write(order.getId(), userId,
                new WriteReviewsRequest(List.of(new ReviewEntry(line, 5, null)))))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ErrorCode.ORDER_NOT_REVIEWABLE);
    }

    @Test
    @DisplayName("one customer cannot review another customer's order")
    void reviewsAreScopedToOwner() {
        Order order = paidOrder();
        UUID line = firstLineId(order);

        assertThatThrownBy(() -> reviewService.write(order.getId(), UUID.randomUUID(),
                new WriteReviewsRequest(List.of(new ReviewEntry(line, 5, null)))))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    @Test
    @DisplayName("a line belonging to a different order is refused")
    void cannotReviewALineOfAnotherOrder() {
        Order mine = paidOrder();
        Order other = paidOrder();
        UUID otherLine = firstLineId(other);

        assertThatThrownBy(() -> reviewService.write(mine.getId(), userId,
                new WriteReviewsRequest(List.of(new ReviewEntry(otherLine, 5, null)))))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    @DisplayName("a product nobody has reviewed reports zero, not null")
    void noReviewsYet() {
        var summary = reviewService.forProduct(UUID.randomUUID(), 0, 10);

        assertThat(summary.averageRating()).isZero();
        assertThat(summary.total()).isZero();
        assertThat(summary.content()).isEmpty();
    }
}
