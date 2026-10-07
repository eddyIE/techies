package vn.techies.ecommerce.order.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import vn.techies.ecommerce.order.AbstractPostgresTest;
import vn.techies.ecommerce.order.api.dto.OrderDtos.ReviewSummaryResponse;
import vn.techies.ecommerce.order.client.AiClient;
import vn.techies.ecommerce.order.client.CatalogClient;
import vn.techies.ecommerce.order.client.IdentityClient;
import vn.techies.ecommerce.order.client.InventoryClient;
import vn.techies.ecommerce.order.client.LoyaltyClient;

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
 * Generation is keyed on the review count, so a product nobody has reviewed since costs
 * nothing however often its page is opened. Every failure degrades to the previous summary or
 * to null, because the reviews underneath the section are the real content.
 */
@SpringBootTest
class ReviewSummaryServiceTest extends AbstractPostgresTest {

    @Autowired
    private ReviewSummaryService service;
    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private IdentityClient identityClient;
    @MockitoBean
    private InventoryClient inventoryClient;
    @MockitoBean
    private LoyaltyClient loyaltyClient;
    @MockitoBean
    private CatalogClient catalogClient;
    @MockitoBean
    private AiClient aiClient;

    private UUID productId;

    @BeforeEach
    void setUp() {
        productId = UUID.randomUUID();
        given(catalogClient.batch(any())).willReturn(List.of(new CatalogClient.ProductSnapshot(
                productId, "Tai nghe ElecGo Pro", new BigDecimal("1490000.00"), "t", true)));
        given(aiClient.summarise(any())).willReturn(new AiClient.ReviewSummary(
                List.of("Âm thanh hay"), List.of("Sạc chậm"), "Phù hợp cho người nghe nhạc."));
    }

    private void review(int rating, String comment) {
        jdbc.update("INSERT INTO product_reviews (id, order_item_id, product_id, user_id, "
                        + "author_name, rating, comment, created_at) "
                        + "VALUES (?, NULL, ?, NULL, 'Khách', ?, ?, NOW())",
                UUID.randomUUID(), productId, rating, comment);
    }

    private void reviews(int count) {
        for (int i = 0; i < count; i++) {
            review(4, "Nhận xét số " + i);
        }
    }

    private AiClient.ReviewSummaryRequest sentToAi() {
        ArgumentCaptor<AiClient.ReviewSummaryRequest> sent =
                ArgumentCaptor.forClass(AiClient.ReviewSummaryRequest.class);
        then(aiClient).should().summarise(sent.capture());
        return sent.getValue();
    }

    @Test
    @DisplayName("A product with no reviews has no summary")
    void returnsNullWithNoReviews() {
        assertThat(service.forProduct(productId)).isNull();
    }

    @Test
    @DisplayName("Two reviews are not a consensus, so there is still no summary")
    void returnsNullBelowThreeReviews() {
        reviews(2);

        assertThat(service.forProduct(productId)).isNull();
    }

    @Test
    @DisplayName("Below the threshold nothing is generated, so no quota is spent")
    void generatesNothingBelowThreeReviews() {
        reviews(2);

        service.forProduct(productId);

        then(aiClient).should(never()).summarise(any());
    }

    @Test
    @DisplayName("Three reviews are enough to summarise")
    void generatesAtThreeReviews() {
        reviews(3);

        assertThat(service.forProduct(productId).pros()).containsExactly("Âm thanh hay");
    }

    @Test
    @DisplayName("The summary reports the count it was written from")
    void reportsTheReviewCount() {
        reviews(5);

        assertThat(service.forProduct(productId).reviewCount()).isEqualTo(5);
    }

    @Test
    @DisplayName("A second read of an unchanged product spends nothing")
    void servesTheCacheWithoutCallingAi() {
        reviews(3);
        service.forProduct(productId);

        service.forProduct(productId);

        then(aiClient).should(times(1)).summarise(any());
    }

    @Test
    @DisplayName("A new review makes the cache stale and the summary is rewritten")
    void regeneratesWhenTheCountChanges() {
        reviews(3);
        service.forProduct(productId);
        review(2, "Thêm một nhận xét nữa");

        service.forProduct(productId);

        then(aiClient).should(times(2)).summarise(any());
    }

    @Test
    @DisplayName("Regenerating keeps one row per product rather than accumulating them")
    void keepsOneRowPerProduct() {
        reviews(3);
        service.forProduct(productId);
        review(2, "Thêm một nhận xét nữa");
        service.forProduct(productId);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM product_review_summaries "
                + "WHERE product_id = ?", Integer.class, productId)).isEqualTo(1);
    }

    @Test
    @DisplayName("The rewritten summary carries the new count")
    void updatesTheCachedCount() {
        reviews(3);
        service.forProduct(productId);
        review(2, "Thêm một nhận xét nữa");

        assertThat(service.forProduct(productId).reviewCount()).isEqualTo(4);
    }

    @Test
    @DisplayName("The product name goes to the model, so it knows what it is summarising")
    void sendsTheProductName() {
        reviews(3);
        service.forProduct(productId);

        assertThat(sentToAi().productName()).isEqualTo("Tai nghe ElecGo Pro");
    }

    @Test
    @DisplayName("Every review goes along with its rating")
    void sendsTheRatings() {
        reviews(3);
        service.forProduct(productId);

        assertThat(sentToAi().reviews()).extracting(AiClient.Review::rating)
                .containsExactly(4, 4, 4);
    }

    @Test
    @DisplayName("No more than the capped number of reviews is sent, whatever the product has")
    void capsTheReviewsSent() {
        reviews(ReviewSummaryService.MAX_REVIEWS_SENT + 5);
        service.forProduct(productId);

        assertThat(sentToAi().reviews()).hasSize(ReviewSummaryService.MAX_REVIEWS_SENT);
    }

    @Test
    @DisplayName("An unreachable ai-service serves the previous summary rather than failing")
    void servesTheStaleSummaryOnFailure() {
        reviews(3);
        service.forProduct(productId);
        review(2, "Thêm một nhận xét nữa");
        willThrow(new IllegalStateException("ai down")).given(aiClient).summarise(any());

        assertThat(service.forProduct(productId).pros()).containsExactly("Âm thanh hay");
    }

    @Test
    @DisplayName("The stale summary still reports the count it was actually written from")
    void staleSummaryKeepsItsOwnCount() {
        reviews(3);
        service.forProduct(productId);
        review(2, "Thêm một nhận xét nữa");
        willThrow(new IllegalStateException("ai down")).given(aiClient).summarise(any());

        assertThat(service.forProduct(productId).reviewCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("With nothing cached, a failure is null rather than an error on the product page")
    void returnsNullOnFailureWithNoCache() {
        reviews(3);
        willThrow(new IllegalStateException("ai down")).given(aiClient).summarise(any());

        assertThat(service.forProduct(productId)).isNull();
    }

    @Test
    @DisplayName("A catalog outage degrades the same way, since the name cannot be read")
    void returnsNullWhenCatalogIsDown() {
        reviews(3);
        willThrow(new IllegalStateException("catalog down")).given(catalogClient).batch(any());

        assertThat(service.forProduct(productId)).isNull();
    }

    @Test
    @DisplayName("An empty summary is still cached, so a product nobody liked is not retried forever")
    void cachesASummaryWithOnlyCons() {
        reviews(3);
        given(aiClient.summarise(any())).willReturn(
                new AiClient.ReviewSummary(List.of(), List.of("Sạc chậm"), "Nên cân nhắc."));
        service.forProduct(productId);

        service.forProduct(productId);

        then(aiClient).should(times(1)).summarise(any());
    }
}
