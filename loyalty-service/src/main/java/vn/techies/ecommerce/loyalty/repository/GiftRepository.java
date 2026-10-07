package vn.techies.ecommerce.loyalty.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import vn.techies.ecommerce.loyalty.domain.Gift;

import java.util.List;
import java.util.UUID;

public interface GiftRepository extends JpaRepository<Gift, UUID> {

    /** Cheapest first, so the exchange screen opens on what the customer can actually afford. */
    List<Gift> findByActiveTrueOrderByPointsCostAsc();
}
