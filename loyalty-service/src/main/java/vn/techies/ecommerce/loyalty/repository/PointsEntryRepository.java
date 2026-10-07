package vn.techies.ecommerce.loyalty.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vn.techies.ecommerce.loyalty.domain.EntryType;
import vn.techies.ecommerce.loyalty.domain.PointsEntry;

import java.util.Optional;
import java.util.UUID;

public interface PointsEntryRepository extends JpaRepository<PointsEntry, UUID> {

    /** Spendable points: earns minus spends, which is every row's sign respected. */
    @Query("SELECT COALESCE(SUM(p.points), 0) FROM PointsEntry p WHERE p.userId = :userId")
    long balanceOf(@Param("userId") UUID userId);

    /**
     * Points ever earned, which is what the tier is derived from. Only the positive rows count,
     * so claiming a gift can never demote a customer for using the feature.
     */
    @Query("SELECT COALESCE(SUM(p.points), 0) FROM PointsEntry p WHERE p.userId = :userId AND p.points > 0")
    long lifetimeOf(@Param("userId") UUID userId);

    /** The idempotency lookup: one ORDER_EARN per order ref, ever. */
    Optional<PointsEntry> findByEntryTypeAndReference(EntryType entryType, String reference);
}
