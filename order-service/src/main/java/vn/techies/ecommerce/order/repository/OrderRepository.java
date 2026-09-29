package vn.techies.ecommerce.order.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vn.techies.ecommerce.order.domain.Order;
import vn.techies.ecommerce.order.domain.OrderStatus;

import java.time.Instant;
import java.util.List;
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

    /**
     * Orders still waiting for a payment that started before {@code cutoff}, oldest first.
     *
     * <p>Items are fetched eagerly: the sweeper needs the lines to restore their stock and
     * runs outside any request, so a lazy collection would have nothing to initialise
     * against. COD needs no exclusion: those orders are confirmed by the saga and never
     * reach this status.
     */
    @Query("""
            SELECT DISTINCT o FROM Order o
              LEFT JOIN FETCH o.items
             WHERE o.status = vn.techies.ecommerce.order.domain.OrderStatus.AWAITING_PAYMENT
               AND o.createdAt < :cutoff
             ORDER BY o.createdAt
            """)
    List<Order> findExpiredPendingPayments(@Param("cutoff") Instant cutoff);

    /**
     * This user's orders that are still waiting to be paid for, with their lines loaded.
     *
     * <p>Checking out releases these first. A customer who opens the payment screen, backs
     * out and taps Checkout again would otherwise place a second order holding the same
     * stock a second time, and could exhaust the availability of the very product they are
     * trying to buy.
     */
    @Query("""
            SELECT DISTINCT o FROM Order o
              LEFT JOIN FETCH o.items
             WHERE o.userId = :userId
               AND o.status = vn.techies.ecommerce.order.domain.OrderStatus.AWAITING_PAYMENT
            """)
    List<Order> findAwaitingPaymentFor(@Param("userId") UUID userId);
}
