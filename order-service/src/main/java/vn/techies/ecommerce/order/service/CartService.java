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
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
        return lineSnapshot(userId, null);
    }

    /**
     * @param selectedItemIds the lines to include, or null for all of them. Every id must be
     *                        in this user's cart: a selection naming something absent is a
     *                        404 rather than a silently smaller order, because quietly
     *                        dropping an item the customer chose is worse than refusing.
     */
    @Transactional(readOnly = true)
    public List<CartLineSnapshot> lineSnapshot(UUID userId, Collection<UUID> selectedItemIds) {
        List<CartItem> items = carts.findByUserId(userId)
                .map(Cart::getItems)
                .orElseGet(List::of);

        if (selectedItemIds == null) {
            return items.stream()
                    .map(i -> new CartLineSnapshot(i.getId(), i.getProductId(), i.getQuantity()))
                    .toList();
        }

        Set<UUID> wanted = new LinkedHashSet<>(selectedItemIds);   // tolerate duplicates
        Map<UUID, CartItem> byId = new LinkedHashMap<>();
        items.forEach(i -> byId.put(i.getId(), i));

        List<UUID> missing = wanted.stream().filter(id -> !byId.containsKey(id)).toList();
        if (!missing.isEmpty()) {
            throw new ApiException(ErrorCode.NOT_FOUND,
                    "These items are not in your cart: " + missing);
        }

        return wanted.stream()
                .map(byId::get)
                .map(i -> new CartLineSnapshot(i.getId(), i.getProductId(), i.getQuantity()))
                .toList();
    }

    /** Removes only the given lines, leaving the rest of the cart intact. */
    @Transactional
    public void removeItems(UUID userId, Collection<UUID> itemIds) {
        carts.findByUserId(userId).ifPresent(cart -> {
            Set<UUID> toRemove = new LinkedHashSet<>(itemIds);
            cart.getItems().removeIf(item -> toRemove.contains(item.getId()));
            cart.touch();
        });
    }

    /**
     * Puts the lines of a failed order back in the cart.
     *
     * <p>Checkout empties the lines it ordered, so a payment that then fails would otherwise
     * leave the customer with nothing to retry. Quantities are **merged and summed** with
     * whatever is in the cart now: they may have added more of the same product while paying,
     * and overwriting would silently discard that.
     *
     * <p>Two things are not put back. A product that has been delisted would sit in the cart
     * and fail their next checkout with PRODUCT_UNAVAILABLE. And the restored quantity is
     * capped at what is actually in stock, because while this order was pending someone else
     * may have bought the units it was holding — that customer got there first, and a cart
     * line nobody can buy is worse than a short one. Both cases are reported back so the app
     * can say what did not come back.
     *
     * <p>Call this only after the order's stock has been released, so the cap sees the units
     * this order was holding.
     */
    @Transactional
    public RestoreSummary restoreFromOrder(UUID userId, List<RestoreLine> lines) {
        Cart cart = loadOrCreate(userId);
        Map<UUID, CatalogClient.ProductSnapshot> products = snapshotOrEmpty(lines);

        int returned = 0;
        List<String> unavailable = new ArrayList<>();

        for (RestoreLine line : lines) {
            CatalogClient.ProductSnapshot product = products.get(line.productId());
            if (product != null && !product.active()) {
                unavailable.add(line.productName());
                continue;
            }

            int existing = cart.findItem(line.productId()).map(CartItem::getQuantity).orElse(0);
            int wanted = existing + line.quantity();
            int target = Math.min(wanted, availableFor(line.productId(), wanted));

            if (target <= existing) {
                unavailable.add(line.productName());
                continue;
            }
            if (existing > 0) {
                cart.findItem(line.productId()).orElseThrow().setQuantity(target);
            } else if (cart.getItems().size() >= Cart.MAX_LINES) {
                unavailable.add(line.productName());
                continue;
            } else {
                cart.addItem(line.productId(), target);
            }
            returned++;
        }

        cart.touch();
        return new RestoreSummary(returned, List.copyOf(unavailable));
    }

    /** Catalog being down must not block a restore; an unknown product is treated as fine. */
    private Map<UUID, CatalogClient.ProductSnapshot> snapshotOrEmpty(List<RestoreLine> lines) {
        try {
            Map<UUID, CatalogClient.ProductSnapshot> byId = new HashMap<>();
            catalogClient.batch(new CatalogClient.BatchRequest(
                    lines.stream().map(RestoreLine::productId).toList())).forEach(p -> byId.put(p.id(), p));
            return byId;
        } catch (Exception ex) {
            log.debug("Catalog unavailable while restoring a cart: {}", ex.toString());
            return Map.of();
        }
    }

    /** Inventory being down must not block a restore either, so fall back to what was asked. */
    private int availableFor(UUID productId, int fallback) {
        try {
            return inventoryClient.getStock(productId).available();
        } catch (Exception ex) {
            log.debug("Stock unavailable while restoring a cart: {}", ex.toString());
            return fallback;
        }
    }

    /** A line of a failed order, ready to go back into the cart. */
    public record RestoreLine(UUID productId, String productName, int quantity) {
    }

    /** @param unavailable the names of lines that could not be returned. */
    public record RestoreSummary(int linesReturned, List<String> unavailable) {
    }

    /** A cart line, detached from Hibernate. {@code itemId} identifies the line to remove. */
    public record CartLineSnapshot(UUID itemId, UUID productId, int quantity) {
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
