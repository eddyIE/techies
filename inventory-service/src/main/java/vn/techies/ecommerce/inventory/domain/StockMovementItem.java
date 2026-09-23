package vn.techies.ecommerce.inventory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Entity
@Table(name = "stock_movement_items")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StockMovementItem {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "movement_id", nullable = false)
    private StockMovement movement;

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Column(nullable = false)
    private int quantity;

    static StockMovementItem create(StockMovement movement, UUID productId, int quantity) {
        StockMovementItem item = new StockMovementItem();
        item.id = UUID.randomUUID();
        item.movement = movement;
        item.productId = productId;
        item.quantity = quantity;
        return item;
    }
}
