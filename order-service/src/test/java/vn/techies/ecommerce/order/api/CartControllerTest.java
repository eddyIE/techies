package vn.techies.ecommerce.order.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import vn.techies.ecommerce.common.security.UserPrincipal;
import vn.techies.ecommerce.order.AbstractPostgresTest;
import vn.techies.ecommerce.order.client.CatalogClient;
import vn.techies.ecommerce.order.client.IdentityClient;
import vn.techies.ecommerce.order.client.InventoryClient;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class CartControllerTest extends AbstractPostgresTest {

    private static final UUID PRODUCT = UUID.fromString("cccccccc-0000-0000-0000-000000000003");

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper json;

    @MockitoBean
    private CatalogClient catalogClient;
    @MockitoBean
    private InventoryClient inventoryClient;
    @MockitoBean
    private IdentityClient identityClient;

    private UUID userId;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        given(catalogClient.batch(any())).willReturn(List.of(
                new CatalogClient.ProductSnapshot(PRODUCT, "Tai nghe Techies",
                        new BigDecimal("1490000.00"), "thumb", true)));
        given(inventoryClient.getStock(any()))
                .willReturn(new InventoryClient.StockResponse(PRODUCT, 42, true));
    }

    private String addBody(int quantity) {
        return """
                {"productId":"%s","quantity":%d}""".formatted(PRODUCT, quantity);
    }

    private String firstItemId() throws Exception {
        String body = mvc.perform(get("/cart").header(UserPrincipal.HEADER_USER_ID, userId))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("items").get(0).get("id").asText();
    }

    @Test
    @DisplayName("an unauthenticated cart request is rejected")
    void requiresIdentity() throws Exception {
        mvc.perform(get("/cart"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("a new user gets an empty cart rather than a 404")
    void emptyCartForNewUser() throws Exception {
        mvc.perform(get("/cart").header(UserPrincipal.HEADER_USER_ID, userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.itemCount").value(0));
    }

    @Test
    @DisplayName("adding an item enriches it with live price and stock")
    void addEnrichesFromDownstream() throws Exception {
        mvc.perform(post("/cart/items").header(UserPrincipal.HEADER_USER_ID, userId)
                        .contentType(MediaType.APPLICATION_JSON).content(addBody(2)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.items[0].name").value("Tai nghe Techies"))
                .andExpect(jsonPath("$.items[0].quantity").value(2))
                .andExpect(jsonPath("$.items[0].available").value(42))
                .andExpect(jsonPath("$.items[0].lineTotal").value(2980000.00))
                .andExpect(jsonPath("$.subtotal").value(2980000.00));
    }

    @Test
    @DisplayName("re-adding the same product increments its line instead of duplicating it")
    void reAddingIncrementsLine() throws Exception {
        mvc.perform(post("/cart/items").header(UserPrincipal.HEADER_USER_ID, userId)
                .contentType(MediaType.APPLICATION_JSON).content(addBody(2)));

        mvc.perform(post("/cart/items").header(UserPrincipal.HEADER_USER_ID, userId)
                        .contentType(MediaType.APPLICATION_JSON).content(addBody(3)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].quantity").value(5));
    }

    @Test
    @DisplayName("updating quantity recalculates the subtotal")
    void updateQuantity() throws Exception {
        mvc.perform(post("/cart/items").header(UserPrincipal.HEADER_USER_ID, userId)
                .contentType(MediaType.APPLICATION_JSON).content(addBody(2)));

        mvc.perform(put("/cart/items/" + firstItemId()).header(UserPrincipal.HEADER_USER_ID, userId)
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"quantity":1}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].quantity").value(1))
                .andExpect(jsonPath("$.subtotal").value(1490000.00));
    }

    @Test
    @DisplayName("setting quantity to zero removes the line")
    void zeroQuantityRemovesLine() throws Exception {
        mvc.perform(post("/cart/items").header(UserPrincipal.HEADER_USER_ID, userId)
                .contentType(MediaType.APPLICATION_JSON).content(addBody(2)));

        mvc.perform(put("/cart/items/" + firstItemId()).header(UserPrincipal.HEADER_USER_ID, userId)
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"quantity":0}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty());
    }

    @Test
    @DisplayName("deleting a line empties the cart")
    void deleteLine() throws Exception {
        mvc.perform(post("/cart/items").header(UserPrincipal.HEADER_USER_ID, userId)
                .contentType(MediaType.APPLICATION_JSON).content(addBody(2)));

        mvc.perform(delete("/cart/items/" + firstItemId()).header(UserPrincipal.HEADER_USER_ID, userId))
                .andExpect(status().isNoContent());

        mvc.perform(get("/cart").header(UserPrincipal.HEADER_USER_ID, userId))
                .andExpect(jsonPath("$.items").isEmpty());
    }

    @Test
    @DisplayName("quantity is validated against the per-line maximum")
    void validatesQuantity() throws Exception {
        mvc.perform(post("/cart/items").header(UserPrincipal.HEADER_USER_ID, userId)
                        .contentType(MediaType.APPLICATION_JSON).content(addBody(100)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        mvc.perform(post("/cart/items").header(UserPrincipal.HEADER_USER_ID, userId)
                        .contentType(MediaType.APPLICATION_JSON).content(addBody(0)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("the cart still renders when catalog and inventory are down, with nulls instead of an error")
    void degradesWhenDownstreamIsDown() throws Exception {
        mvc.perform(post("/cart/items").header(UserPrincipal.HEADER_USER_ID, userId)
                .contentType(MediaType.APPLICATION_JSON).content(addBody(2)));

        willThrow(new RuntimeException("catalog down")).given(catalogClient).batch(any());
        willThrow(new RuntimeException("inventory down")).given(inventoryClient).getStock(any());

        mvc.perform(get("/cart").header(UserPrincipal.HEADER_USER_ID, userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].quantity").value(2))
                .andExpect(jsonPath("$.items[0].name").doesNotExist())
                .andExpect(jsonPath("$.items[0].available").doesNotExist());
    }

    @Test
    @DisplayName("one user's cart is invisible to another")
    void cartsAreScopedPerUser() throws Exception {
        mvc.perform(post("/cart/items").header(UserPrincipal.HEADER_USER_ID, userId)
                .contentType(MediaType.APPLICATION_JSON).content(addBody(2)));

        mvc.perform(get("/cart").header(UserPrincipal.HEADER_USER_ID, UUID.randomUUID()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty());
    }
}
