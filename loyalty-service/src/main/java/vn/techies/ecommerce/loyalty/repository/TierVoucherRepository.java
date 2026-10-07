package vn.techies.ecommerce.loyalty.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import vn.techies.ecommerce.loyalty.domain.TierVoucher;

import java.util.List;
import java.util.UUID;

public interface TierVoucherRepository extends JpaRepository<TierVoucher, String> {

    boolean existsByUserIdAndTier(UUID userId, short tier);

    List<TierVoucher> findByUserIdOrderByTierAsc(UUID userId);
}
