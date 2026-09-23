package vn.techies.ecommerce.order.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Entity
@Table(name = "carts")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Cart {

    /** A cart may hold at most this many distinct products. */
    public static final int MAX_LINES = 50;
    public static final int MAX_QUANTITY_PER_LINE = 99;

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @OneToMany(mappedBy = "cart", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<CartItem> items = new ArrayList<>();

    public static Cart createFor(UUID userId) {
        Cart cart = new Cart();
        cart.id = UUID.randomUUID();
        cart.userId = userId;
        Instant now = Instant.now();
        cart.createdAt = now;
        cart.updatedAt = now;
        return cart;
    }

    public Optional<CartItem> findItem(UUID productId) {
        return items.stream().filter(i -> i.getProductId().equals(productId)).findFirst();
    }

    public void addItem(UUID productId, int quantity) {
        items.add(CartItem.create(this, productId, quantity));
        touch();
    }

    public void removeItem(CartItem item) {
        items.remove(item);
        touch();
    }

    public void clear() {
        items.clear();
        touch();
    }

    public void touch() {
        this.updatedAt = Instant.now();
    }
}
