package vn.techies.ecommerce.ai.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vn.techies.ecommerce.ai.client.CatalogClient;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SystemPromptBuilderTest {

    private final SystemPromptBuilder builder = new SystemPromptBuilder();

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
    @DisplayName("replies are constrained to short Vietnamese, for a phone popup")
    void constrainsLengthAndLanguage() {
        String prompt = builder.build(product(), 1);

        assertThat(prompt).contains("tiếng Việt");
        assertThat(prompt).contains("2-3 câu");
    }
}
