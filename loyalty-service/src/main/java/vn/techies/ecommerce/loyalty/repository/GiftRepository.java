package vn.techies.ecommerce.loyalty.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vn.techies.ecommerce.loyalty.domain.Gift;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface GiftRepository extends JpaRepository<Gift, UUID> {

    /** Cheapest first, so the exchange screen opens on what the customer can actually afford. */
    List<Gift> findByActiveTrueOrderByPointsCostAsc();

    Optional<Gift> findByIdAndActiveTrue(UUID id);

    /**
     * The atomic conditional decrement, the same guard inventory uses on the last unit of stock.
     *
     * <p>Postgres evaluates {@code stock >= 1} as part of the write, so ten customers racing for
     * one unit cannot all see it available. Returns rows updated: 0 means it is gone.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE Gift g
               SET g.stock = g.stock - 1
             WHERE g.id = :id
               AND g.stock >= 1
            """)
    int takeOneFromStock(@Param("id") UUID id);
}
