package vn.techies.ecommerce.order.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vn.techies.ecommerce.order.domain.Order;
import vn.techies.ecommerce.order.domain.OrderStatus;

import java.util.Optional;
import java.util.UUID;

public interface OrderRepository extends JpaRepository<Order, UUID> {

    @Query("""
            SELECT o FROM Order o
             WHERE o.userId = :userId
               AND (:status IS NULL OR o.status = :status)
             ORDER BY o.createdAt DESC
            """)
    Page<Order> findForUser(@Param("userId") UUID userId,
                            @Param("status") OrderStatus status,
                            Pageable pageable);

    Optional<Order> findByOrderRef(String orderRef);
}
