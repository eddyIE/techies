package vn.techies.ecommerce.order.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import vn.techies.ecommerce.order.domain.Cart;

import java.util.Optional;
import java.util.UUID;

public interface CartRepository extends JpaRepository<Cart, UUID> {

    Optional<Cart> findByUserId(UUID userId);
}
