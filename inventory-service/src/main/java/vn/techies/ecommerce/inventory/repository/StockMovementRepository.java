package vn.techies.ecommerce.inventory.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import vn.techies.ecommerce.inventory.domain.MovementType;
import vn.techies.ecommerce.inventory.domain.StockMovement;

import java.util.Optional;
import java.util.UUID;

public interface StockMovementRepository extends JpaRepository<StockMovement, UUID> {

    Optional<StockMovement> findByOrderRefAndType(String orderRef, MovementType type);

    boolean existsByOrderRefAndType(String orderRef, MovementType type);
}
