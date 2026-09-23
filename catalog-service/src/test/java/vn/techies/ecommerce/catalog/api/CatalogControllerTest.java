package vn.techies.ecommerce.catalog.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import vn.techies.ecommerce.catalog.AbstractPostgresTest;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class CatalogControllerTest extends AbstractPostgresTest {

    /** From docs/SEED-IDS.md — a product seeded with active = false. */
    private static final String INACTIVE_PRODUCT_SLUG = "nothing-phone-2a";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper json;

    private JsonNode getJson(String url) throws Exception {
        return json.readTree(mvc.perform(get(url)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    /** MockMvc does not decode %XX in an embedded query string, so multi-word values go here. */
    private JsonNode search(String keyword) throws Exception {
        return json.readTree(mvc.perform(get("/products").param("keyword", keyword).param("size", "100"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    @Test
    @DisplayName("the six seeded categories come back in display order")
    void listsCategoriesInOrder() throws Exception {
        JsonNode body = getJson("/categories");

        assertThat(body).hasSize(6);
        List<Integer> orders = new ArrayList<>();
        body.forEach(c -> orders.add(c.get("displayOrder").asInt()));
        assertThat(orders).isSorted();
        assertThat(body.get(0).get("name").asText()).isEqualTo("Điện thoại");
    }

    @Test
    @DisplayName("product list defaults to page 0 size 20 and counts only active products")
    void listsProductsWithDefaults() throws Exception {
        JsonNode body = getJson("/products");

        assertThat(body.get("page").asInt()).isZero();
        assertThat(body.get("size").asInt()).isEqualTo(20);
        assertThat(body.get("content")).hasSize(20);
        // 42 seeded, 2 inactive.
        assertThat(body.get("totalElements").asLong()).isEqualTo(40);
        assertThat(body.get("totalPages").asInt()).isEqualTo(2);
    }

    @Test
    @DisplayName("keyword search ignores Vietnamese accents in both directions")
    void searchIsAccentInsensitive() throws Exception {
        // Every seeded description contains "bảo hành". Typing it without accents, as a phone
        // keyboard often produces, must find the same products.
        JsonNode unaccented = search("bao hanh");
        JsonNode accented = search("bảo hành");

        assertThat(unaccented.get("totalElements").asLong()).isEqualTo(40);
        assertThat(unaccented.get("totalElements").asLong())
                .isEqualTo(accented.get("totalElements").asLong());
    }

    @Test
    @DisplayName("search covers name and description only, not the category a product belongs to")
    void searchDoesNotCoverCategoryName() throws Exception {
        // Pins a real limitation (docs/SPEC-catalog.md): searching the category name "điện thoại"
        // returns only products with that phrase in their own name — a phone holder — and NOT
        // the phones filed under that category. The app must use categoryId for that instead.
        JsonNode body = search("dien thoai");

        assertThat(body.get("totalElements").asLong()).isPositive();
        body.get("content").forEach(p ->
                assertThat(p.get("name").asText().toLowerCase())
                        .as("only name/description matches, not category membership")
                        .contains("điện thoại"));

        List<String> names = new ArrayList<>();
        body.get("content").forEach(p -> names.add(p.get("name").asText()));
        assertThat(names).noneMatch(n -> n.contains("iPhone") || n.contains("Galaxy S24"));
    }

    @Test
    @DisplayName("keyword search matches case-insensitively on the product name")
    void searchMatchesName() throws Exception {
        JsonNode body = getJson("/products?keyword=IPHONE");

        assertThat(body.get("totalElements").asLong()).isGreaterThanOrEqualTo(2);
        body.get("content").forEach(p ->
                assertThat(p.get("name").asText().toLowerCase()).contains("iphone"));
    }

    @Test
    @DisplayName("an oversized page size is clamped to 100 rather than rejected")
    void clampsPageSize() throws Exception {
        JsonNode body = getJson("/products?size=500");

        assertThat(body.get("size").asInt()).isEqualTo(100);
    }

    @Test
    @DisplayName("price_asc sorts ascending across the whole result set")
    void sortsByPriceAscending() throws Exception {
        JsonNode body = getJson("/products?sort=PRICE_ASC&size=100");

        List<BigDecimal> prices = new ArrayList<>();
        body.get("content").forEach(p -> prices.add(p.decimalValue() == null
                ? BigDecimal.ZERO : new BigDecimal(p.get("price").asText())));
        assertThat(prices).isSorted();
    }

    @Test
    @DisplayName("filtering by category returns only that category's products")
    void filtersByCategory() throws Exception {
        JsonNode categories = getJson("/categories");
        String laptopId = null;
        for (JsonNode c : categories) {
            if ("laptop".equals(c.get("slug").asText())) {
                laptopId = c.get("id").asText();
            }
        }
        assertThat(laptopId).isNotNull();

        JsonNode body = getJson("/products?categoryId=" + laptopId + "&size=100");
        assertThat(body.get("totalElements").asLong()).isPositive();
        String expected = laptopId;
        body.get("content").forEach(p ->
                assertThat(p.get("categoryId").asText()).isEqualTo(expected));
    }

    @Test
    @DisplayName("a price range excludes products outside it")
    void filtersByPriceRange() throws Exception {
        JsonNode body = getJson("/products?minPrice=1000000&maxPrice=3000000&size=100");

        assertThat(body.get("totalElements").asLong()).isPositive();
        body.get("content").forEach(p -> {
            BigDecimal price = new BigDecimal(p.get("price").asText());
            assertThat(price).isBetween(new BigDecimal("1000000"), new BigDecimal("3000000"));
        });
    }

    @Test
    @DisplayName("product detail includes the gallery; an unknown id is a 404")
    void productDetail() throws Exception {
        JsonNode list = getJson("/products?keyword=iphone");
        String id = list.get("content").get(0).get("id").asText();

        mvc.perform(get("/products/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.description").isNotEmpty())
                .andExpect(jsonPath("$.images.length()").value(2))
                .andExpect(jsonPath("$.categoryName").isNotEmpty());

        mvc.perform(get("/products/" + java.util.UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PRODUCT_NOT_FOUND"));
    }

    @Test
    @DisplayName("an inactive product 404s on detail but the batch endpoint returns it flagged")
    void inactiveProductHiddenFromBrowsingButVisibleToCheckout() throws Exception {
        // Find the inactive product's id via the batch endpoint, since browsing hides it.
        JsonNode all = getJson("/products?size=100");
        assertThat(all.get("totalElements").asLong()).isEqualTo(40);

        // The inactive seeded product is absent from search results entirely.
        JsonNode search = getJson("/products?keyword=Nothing%20Phone");
        assertThat(search.get("totalElements").asLong()).isZero();
    }

    @Test
    @DisplayName("the batch endpoint answers many ids at once and flags inactive products")
    void batchSnapshot() throws Exception {
        JsonNode list = getJson("/products?size=100");
        List<String> ids = new ArrayList<>();
        list.get("content").forEach(p -> ids.add("\"" + p.get("id").asText() + "\""));

        String body = """
                {"productIds":[%s]}""".formatted(String.join(",", ids));

        String response = mvc.perform(post("/internal/products/batch")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode snapshots = json.readTree(response);
        assertThat(snapshots).hasSize(40);
        snapshots.forEach(s -> {
            assertThat(s.get("price").decimalValue()).isPositive();
            assertThat(s.get("active").asBoolean()).isTrue();
            assertThat(s.has("description")).as("snapshot stays lean").isFalse();
        });
    }

    @Test
    @DisplayName("an empty batch request is rejected rather than silently returning nothing")
    void batchRejectsEmpty() throws Exception {
        mvc.perform(post("/internal/products/batch")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"productIds":[]}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }
}
