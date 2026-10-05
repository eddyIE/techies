package vn.techies.ecommerce.order.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
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

    /**
     * Ordered by when each line was added, so a line keeps its place in the list.
     *
     * <p>Without this the order is whatever Postgres returns, which is physical row order.
     * An UPDATE there does not edit in place: MVCC writes a new tuple version and retires the
     * old one, so the row usually moves to the end of the heap. The effect in the app was that
     * changing a quantity sent that line to the bottom of the cart, out from under the finger
     * that just tapped it.
     *
     * <p>{@code addedAt} is assigned once in {@link CartItem#create} and never reassigned, so
     * editing a quantity cannot move a line. Removing one and adding it back makes a new line
     * with a new timestamp, which correctly goes last. {@code id} only breaks ties between two
     * lines added in the same instant.
     */
    @OneToMany(mappedBy = "cart", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("addedAt ASC, id ASC")
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
