package vn.techies.ecommerce.order.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import vn.techies.ecommerce.order.AbstractPostgresTest;
import vn.techies.ecommerce.order.api.dto.CartDtos.AddCartItemRequest;
import vn.techies.ecommerce.order.api.dto.CartDtos.CartItemResponse;
import vn.techies.ecommerce.order.client.CatalogClient;
import vn.techies.ecommerce.order.client.IdentityClient;
import vn.techies.ecommerce.order.client.InventoryClient;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

/**
 * A cart line keeps its place when its quantity changes.
 *
 * <p>Without an explicit order the list came back in physical row order, and Postgres rewrites
 * a row on UPDATE rather than editing it in place — so editing a quantity sent that line to the
 * bottom of the cart, under the finger that had just tapped it.
 */
@SpringBootTest
class CartOrderingTest extends AbstractPostgresTest {

    private static final UUID FIRST = UUID.fromString("dddddddd-0000-0000-0000-000000000001");
    private static final UUID SECOND = UUID.fromString("dddddddd-0000-0000-0000-000000000002");
    private static final UUID THIRD = UUID.fromString("dddddddd-0000-0000-0000-000000000003");

    @Autowired
    private CartService cartService;
    @Autowired
    private JdbcTemplate jdbc;

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
                snapshot(FIRST, "Sản phẩm 1"), snapshot(SECOND, "Sản phẩm 2"),
                snapshot(THIRD, "Sản phẩm 3")));
        given(inventoryClient.getStock(any()))
                .willReturn(new InventoryClient.StockResponse(FIRST, 99, true));

        cartService.add(userId, new AddCartItemRequest(FIRST, 1));
        cartService.add(userId, new AddCartItemRequest(SECOND, 1));
        cartService.add(userId, new AddCartItemRequest(THIRD, 1));
    }

    private static CatalogClient.ProductSnapshot snapshot(UUID id, String name) {
        return new CatalogClient.ProductSnapshot(id, name, new BigDecimal("100000.00"), "t", true);
    }

    private List<UUID> order() {
        return cartService.view(userId).items().stream().map(CartItemResponse::productId).toList();
    }

    private UUID lineIdOf(UUID productId) {
        return cartService.view(userId).items().stream()
                .filter(i -> i.productId().equals(productId))
                .map(CartItemResponse::id)
                .findFirst().orElseThrow();
    }

    @Test
    @DisplayName("lines come back in the order they were added")
    void keepsInsertionOrder() {
        assertThat(order()).containsExactly(FIRST, SECOND, THIRD);
    }

    @Test
    @DisplayName("raising a quantity does not move that line")
    void raisingQuantityKeepsPosition() {
        cartService.updateQuantity(userId, lineIdOf(FIRST), 7);

        assertThat(order()).containsExactly(FIRST, SECOND, THIRD);
    }

    @Test
    @DisplayName("re-adding a product that is already in the cart does not move its line")
    void addingMoreOfTheSameKeepsPosition() {
        // `add` on an existing product increments the line rather than creating a second one,
        // which is still an UPDATE and used to move it.
        cartService.add(userId, new AddCartItemRequest(FIRST, 3));

        assertThat(order()).containsExactly(FIRST, SECOND, THIRD);
        assertThat(cartService.view(userId).items().get(0).quantity()).isEqualTo(4);
    }

    @Test
    @DisplayName("editing every line in reverse still leaves the original order")
    void repeatedEditsNeverReorder() {
        cartService.updateQuantity(userId, lineIdOf(THIRD), 5);
        cartService.updateQuantity(userId, lineIdOf(SECOND), 4);
        cartService.updateQuantity(userId, lineIdOf(FIRST), 3);

        assertThat(order()).containsExactly(FIRST, SECOND, THIRD);
    }

    @Test
    @DisplayName("the list follows addedAt, not the order the rows happen to sit in")
    void followsAddedAtNotPhysicalOrder() {
        // The behavioural tests above cannot fail without the mapping: with three rows in a
        // single page Postgres usually does a HOT update and the row never moves, so the
        // original symptom will not reproduce on demand.
        //
        // This drives it from the other end. The rows were inserted FIRST, SECOND, THIRD, so
        // that is their physical order. Backdating THIRD ahead of the others means insertion
        // order and addedAt order now disagree, and only a mapping that actually sorts by
        // addedAt can return THIRD first.
        jdbc.update("UPDATE cart_items SET added_at = added_at - INTERVAL '1 hour' "
                + "WHERE product_id = ?", THIRD);

        assertThat(order()).containsExactly(THIRD, FIRST, SECOND);
    }

    @Test
    @DisplayName("a removed and re-added product goes to the end, because it is a new line")
    void reAddedProductGoesLast() {
        cartService.remove(userId, lineIdOf(FIRST));
        assertThat(order()).containsExactly(SECOND, THIRD);

        cartService.add(userId, new AddCartItemRequest(FIRST, 1));

        assertThat(order()).containsExactly(SECOND, THIRD, FIRST);
    }

    @Test
    @DisplayName("setting a quantity to zero removes the line, and re-adding puts it last")
    void zeroQuantityRemovesThenReAddsLast() {
        // quantity 0 is the app's "remove" gesture, so it must behave like a removal.
        cartService.updateQuantity(userId, lineIdOf(SECOND), 0);
        assertThat(order()).containsExactly(FIRST, THIRD);

        cartService.add(userId, new AddCartItemRequest(SECOND, 2));

        assertThat(order()).containsExactly(FIRST, THIRD, SECOND);
    }
}
