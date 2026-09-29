package vn.techies.ecommerce.ai.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vn.techies.ecommerce.ai.client.CatalogClient;
import vn.techies.ecommerce.ai.config.GeminiProperties;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SystemPromptBuilderTest {

    private static SystemPromptBuilder builderWith(boolean webSearch) {
        return new SystemPromptBuilder(new GeminiProperties("k", "url", "model", 60, 10, 3, webSearch));
    }

    private final SystemPromptBuilder builder = builderWith(true);

    private CatalogClient.ProductDetail product() {
        return new CatalogClient.ProductDetail(UUID.randomUUID(), "iPhone 15 Pro Max 256GB",
                "iphone-15-pro-max", "Hàng chính hãng, bảo hành 12 tháng.",
                new BigDecimal("31990000.00"), "thumb", UUID.randomUUID(), "Điện thoại", List.of());
    }

    @Test
    @DisplayName("the real product data is injected, so the model never relies on the client")
    void injectsProductFacts() {
        String prompt = builder.build(product(), 120);

        assertThat(prompt).contains("iPhone 15 Pro Max 256GB", "Điện thoại",
                "Hàng chính hãng, bảo hành 12 tháng.");
    }

    @Test
    @DisplayName("the price is formatted as VND, not raw digits")
    void formatsPriceForVietnam() {
        assertThat(builder.build(product(), 10)).contains("31.990.000");
    }

    @Test
    @DisplayName("stock is stated in words the model can use directly")
    void statesStock() {
        assertThat(builder.build(product(), 5)).contains("còn hàng");
        assertThat(builder.build(product(), 0)).contains("hết hàng");
        assertThat(builder.build(product(), null)).contains("không rõ");
    }

    @Test
    @DisplayName("the anti-invention rule is present — the seeded descriptions are thin")
    void forbidsInvention() {
        String prompt = builder.build(product(), 1);

        assertThat(prompt).contains("KHÔNG bịa");
        assertThat(prompt).contains("không có thông tin");
    }

    @Test
    @DisplayName("it instructs the model to search rather than recall other products")
    void instructsToolUse() {
        assertThat(builder.build(product(), 1)).contains("search_products");
    }

    @Test
    @DisplayName("specifications the catalogue lacks are looked up rather than refused")
    void groundsMissingSpecifications() {
        String prompt = builder.build(product(), 1);

        assertThat(prompt).contains("Google Search");
        assertThat(prompt).contains("dung lượng pin");
    }

    @Test
    @DisplayName("a looked-up figure must be labelled as the manufacturer's, not the store's")
    void labelsGroundedFactsAsReference() {
        String prompt = builder.build(product(), 1);

        // The phrase wraps across two lines in the text block, so match either side of it.
        assertThat(prompt).contains("thông số tham khảo");
        assertThat(prompt).contains("không phải cam kết của cửa hàng");
    }

    @Test
    @DisplayName("store facts stay off the internet — the web contradicts our own warranty")
    void keepsStoreFactsOffTheWeb() {
        String prompt = builder.build(product(), 1);

        assertThat(prompt).contains("KHÔNG tra trên internet");
        assertThat(prompt).contains("chính sách riêng của Techies");
    }

    @Test
    @DisplayName("with search off the model is told it has no lookup, not to look things up")
    void withoutSearchTheRuleInverts() {
        String prompt = builderWith(false).build(product(), 1);

        assertThat(prompt).doesNotContain("Google Search");
        assertThat(prompt).contains("KHÔNG có công cụ tra cứu");
        // The phrase wraps across two lines in the text block, so match one side of it.
        assertThat(prompt).contains("bằng trí nhớ của bạn");
    }

    @Test
    @DisplayName("with search off it must not dress a remembered number as a manufacturer spec")
    void withoutSearchItCannotClaimAReference() {
        assertThat(builderWith(false).build(product(), 1))
                .contains("KHÔNG gọi con số tự nhớ");
    }

    @Test
    @DisplayName("the pronouns are pinned, so the assistant does not drift mid-conversation")
    void pinsPronouns() {
        String prompt = builder.build(product(), 1);

        assertThat(prompt).contains("XƯNG HÔ");
        assertThat(prompt).contains("\"em\"");
        assertThat(prompt).contains("\"anh/chị\"");
    }

    @Test
    @DisplayName("search results are not re-listed: the app already shows them as cards")
    void doesNotRelistSearchResults() {
        String prompt = builder.build(product(), 1);

        assertThat(prompt).contains("KHÔNG liệt kê lại");
        assertThat(prompt).contains("thẻ bấm được");
    }

    @Test
    @DisplayName("it may not invent features for searched products, knowing only name and price")
    void cannotInventFeaturesForSearchResults() {
        assertThat(builder.build(product(), 1)).contains("KHÔNG bịa thêm tính năng");
    }

    @Test
    @DisplayName("it may not quote a count larger than the cards on screen")
    void doesNotAnnounceUnshownProducts() {
        String prompt = builder.build(product(), 1);

        assertThat(prompt).contains("chỉ được nói đúng số sản phẩm đang hiển thị");
        assertThat(prompt).contains("KHÔNG nêu con số");
    }

    @Test
    @DisplayName("only in-stock products are recommended")
    void recommendsOnlyInStock() {
        assertThat(builder.build(product(), 1)).contains("Chỉ gợi ý những mẫu đang");
    }

    @Test
    @DisplayName("replies are constrained to short Vietnamese, for a phone popup")
    void constrainsLengthAndLanguage() {
        String prompt = builder.build(product(), 1);

        assertThat(prompt).contains("tiếng Việt");
        assertThat(prompt).contains("2-3 câu");
    }
}
