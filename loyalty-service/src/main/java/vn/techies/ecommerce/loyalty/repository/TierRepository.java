package vn.techies.ecommerce.loyalty.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import vn.techies.ecommerce.loyalty.domain.Tier;

import java.util.List;

public interface TierRepository extends JpaRepository<Tier, Short> {

    List<Tier> findAllByOrderByTierAsc();
}
