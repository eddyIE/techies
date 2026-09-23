package vn.techies.ecommerce.inventory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "stock_items")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StockItem {

    @Id
    @Column(name = "product_id")
    private UUID productId;

    @Column(nullable = false)
    private int available;

    /**
     * Optimistic lock. Two concurrent checkouts for the last unit both read version N; the
     * loser's update matches no row and Hibernate raises OptimisticLockException.
     */
    @Version
    @Column(nullable = false)
    private long version;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public boolean isInStock() {
        return available > 0;
    }
}
