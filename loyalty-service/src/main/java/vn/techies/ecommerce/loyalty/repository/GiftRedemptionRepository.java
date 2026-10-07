package vn.techies.ecommerce.loyalty.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vn.techies.ecommerce.loyalty.domain.GiftRedemption;

import java.util.List;
import java.util.Set;
import java.util.UUID;

public interface GiftRedemptionRepository extends JpaRepository<GiftRedemption, UUID> {

    /** Drives `alreadyClaimed` on the catalogue in one query rather than one per gift. */
    @Query("SELECT r.giftId FROM GiftRedemption r WHERE r.userId = :userId")
    Set<UUID> findGiftIdsByUserId(@Param("userId") UUID userId);

    List<GiftRedemption> findByUserIdOrderByClaimedAtDesc(UUID userId);

    boolean existsByUserIdAndGiftId(UUID userId, UUID giftId);
}
