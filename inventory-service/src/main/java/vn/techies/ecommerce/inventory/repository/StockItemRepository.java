package vn.techies.ecommerce.inventory.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vn.techies.ecommerce.inventory.domain.StockItem;

import java.util.UUID;

public interface StockItemRepository extends JpaRepository<StockItem, UUID> {

    /**
     * The atomic conditional decrement — the primary oversell guard.
     *
     * <p>The {@code available >= :quantity} predicate is evaluated by Postgres as part of the
     * write, so two concurrent callers cannot both see enough stock and both succeed. Returns
     * the number of rows updated: 0 means there was not enough stock, and the caller must
     * treat that as a failure rather than retrying blindly.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE StockItem s
               SET s.available = s.available - :quantity,
                   s.version = s.version + 1,
                   s.updatedAt = CURRENT_TIMESTAMP
             WHERE s.productId = :productId
               AND s.available >= :quantity
            """)
    int deductIfAvailable(@Param("productId") UUID productId, @Param("quantity") int quantity);

    /** Compensation. Unconditional: returning stock can never fail for lack of room. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE StockItem s
               SET s.available = s.available + :quantity,
                   s.version = s.version + 1,
                   s.updatedAt = CURRENT_TIMESTAMP
             WHERE s.productId = :productId
            """)
    int restore(@Param("productId") UUID productId, @Param("quantity") int quantity);
}
