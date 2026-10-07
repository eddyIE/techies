package vn.techies.ecommerce.loyalty.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vn.techies.ecommerce.loyalty.domain.TierVoucher;

import java.util.List;
import java.util.UUID;

public interface TierVoucherRepository extends JpaRepository<TierVoucher, String> {

    boolean existsByUserIdAndTier(UUID userId, short tier);

    List<TierVoucher> findByUserIdOrderByTierAsc(UUID userId);

    /**
     * The atomic claim. {@code consumedOrderRef IS NULL} is evaluated as part of the write, so
     * two checkouts cannot both spend one voucher. 0 rows means someone else already has it.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE TierVoucher v
               SET v.consumedOrderRef = :orderRef,
                   v.consumedAt = CURRENT_TIMESTAMP
             WHERE v.code = :code
               AND v.consumedOrderRef IS NULL
            """)
    int consumeIfUnspent(@Param("code") String code, @Param("orderRef") String orderRef);

    /**
     * Compensation, keyed on the order that holds it: one order can never release the hold
     * another order has, and releasing what nobody spent updates nothing.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE TierVoucher v
               SET v.consumedOrderRef = NULL,
                   v.consumedAt = NULL
             WHERE v.code = :code
               AND v.consumedOrderRef = :orderRef
            """)
    int releaseIfHeldBy(@Param("code") String code, @Param("orderRef") String orderRef);
}
