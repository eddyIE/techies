package vn.techies.ecommerce.inventory.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * An audit record of stock movement, and the idempotency key for the operation that caused it.
 * The unique (order_ref, type) constraint is what makes deduct and restore safe to retry.
 */
@Entity
@Table(name = "stock_movements")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StockMovement {

    @Id
    private UUID id;

    @Column(name = "order_ref", nullable = false, length = 20)
    private String orderRef;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private MovementType type;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @OneToMany(mappedBy = "movement", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<StockMovementItem> items = new ArrayList<>();

    public static StockMovement create(String orderRef, MovementType type) {
        StockMovement m = new StockMovement();
        m.id = UUID.randomUUID();
        m.orderRef = orderRef;
        m.type = type;
        m.createdAt = Instant.now();
        return m;
    }

    public void addItem(UUID productId, int quantity) {
        items.add(StockMovementItem.create(this, productId, quantity));
    }
}
