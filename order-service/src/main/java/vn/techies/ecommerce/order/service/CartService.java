package vn.techies.ecommerce.order.service;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.techies.ecommerce.common.error.ApiException;
import vn.techies.ecommerce.common.error.ErrorCode;
import vn.techies.ecommerce.order.api.dto.CartDtos.AddCartItemRequest;
import vn.techies.ecommerce.order.api.dto.CartDtos.CartItemResponse;
import vn.techies.ecommerce.order.api.dto.CartDtos.CartResponse;
import vn.techies.ecommerce.order.client.CatalogClient;
import vn.techies.ecommerce.order.client.InventoryClient;
import vn.techies.ecommerce.order.domain.Cart;
import vn.techies.ecommerce.order.domain.CartItem;
import vn.techies.ecommerce.order.repository.CartRepository;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CartService {

    private static final Logger log = LoggerFactory.getLogger(CartService.class);

    private final CartRepository carts;
    private final CatalogClient catalogClient;
    private final InventoryClient inventoryClient;

    @Transactional
    public Cart loadOrCreate(UUID userId) {
        return carts.findByUserId(userId).orElseGet(() -> carts.save(Cart.createFor(userId)));
    }

    /**
     * The cart's contents as plain values, read inside the transaction.
     *
     * <p>Checkout must not hold a detached Cart and walk its lazy item collection afterwards —
     * that throws LazyInitializationException. Returning a snapshot keeps the entity's
     * lifetime inside the transaction that loaded it.
     */
    @Transactional(readOnly = true)
    public List<CartLineSnapshot> lineSnapshot(UUID userId) {
        return carts.findByUserId(userId)
                .map(cart -> cart.getItems().stream()
                        .map(i -> new CartLineSnapshot(i.getProductId(), i.getQuantity()))
                        .toList())
                .orElseGet(List::of);
    }

    /** A cart line, detached from Hibernate. */
    public record CartLineSnapshot(UUID productId, int quantity) {
    }

    @Transactional
    public CartResponse view(UUID userId) {
        return enrich(loadOrCreate(userId));
    }

    /**
     * Adds a product, or increases the quantity if it is already in the cart.
     *
     * <p>Stock is deliberately NOT checked here. It can change between adding to a cart and
     * checking out, so checkout is the only place that can meaningfully decide — see step 5
     * of the saga.
     */
    @Transactional
    public CartResponse add(UUID userId, AddCartItemRequest request) {
        Cart cart = loadOrCreate(userId);

        cart.findItem(request.productId()).ifPresentOrElse(
                existing -> existing.addQuantity(request.quantity()),
                () -> {
                    if (cart.getItems().size() >= Cart.MAX_LINES) {
                        throw new ApiException(ErrorCode.CONFLICT,
                                "A cart may hold at most " + Cart.MAX_LINES + " different products");
                    }
                    cart.addItem(request.productId(), request.quantity());
                });
        cart.touch();
        return enrich(cart);
    }

    @Transactional
    public CartResponse updateQuantity(UUID userId, UUID itemId, int quantity) {
        Cart cart = loadOrCreate(userId);
        CartItem item = cart.getItems().stream()
                .filter(i -> i.getId().equals(itemId))
                .findFirst()
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Cart item not found"));

        if (quantity == 0) {
            cart.removeItem(item);
        } else {
            item.setQuantity(quantity);
            cart.touch();
        }
        return enrich(cart);
    }

    @Transactional
    public CartResponse remove(UUID userId, UUID itemId) {
        return updateQuantity(userId, itemId, 0);
    }

    @Transactional
    public void clear(UUID userId) {
        carts.findByUserId(userId).ifPresent(Cart::clear);
    }

    /**
     * Joins the cart's product ids to live catalog and stock data.
     *
     * <p>Degrades rather than fails: if catalog or inventory is unreachable the cart still
     * renders, with nulls in the enriched fields. A shopper should be able to see their cart
     * even when a downstream service is down.
     */
    private CartResponse enrich(Cart cart) {
        List<CartItem> items = cart.getItems();
        if (items.isEmpty()) {
            return new CartResponse(List.of(), BigDecimal.ZERO, 0);
        }

        List<UUID> productIds = items.stream().map(CartItem::getProductId).toList();

        Map<UUID, CatalogClient.ProductSnapshot> products = new HashMap<>();
        try {
            catalogClient.batch(new CatalogClient.BatchRequest(productIds))
                    .forEach(p -> products.put(p.id(), p));
        } catch (Exception ex) {
            log.warn("Catalog unavailable while rendering cart {}: {}", cart.getId(), ex.toString());
        }

        Map<UUID, Integer> stock = new HashMap<>();
        for (UUID productId : productIds) {
            try {
                stock.put(productId, inventoryClient.getStock(productId).available());
            } catch (Exception ex) {
                log.warn("Stock unavailable for product {}: {}", productId, ex.toString());
            }
        }

        BigDecimal subtotal = BigDecimal.ZERO;
        List<CartItemResponse> lines = new java.util.ArrayList<>();
        for (CartItem item : items) {
            CatalogClient.ProductSnapshot product = products.get(item.getProductId());
            BigDecimal unitPrice = product == null ? null : product.price();
            BigDecimal lineTotal = unitPrice == null
                    ? null : unitPrice.multiply(BigDecimal.valueOf(item.getQuantity()));
            if (lineTotal != null) {
                subtotal = subtotal.add(lineTotal);
            }
            lines.add(new CartItemResponse(item.getId(), item.getProductId(),
                    product == null ? null : product.name(), unitPrice, item.getQuantity(),
                    lineTotal, product == null ? null : product.thumbnailUrl(),
                    stock.get(item.getProductId())));
        }

        return new CartResponse(lines, subtotal, items.size());
    }
}
