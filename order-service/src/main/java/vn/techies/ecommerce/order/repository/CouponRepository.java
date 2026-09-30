package vn.techies.ecommerce.order.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import vn.techies.ecommerce.order.domain.Coupon;

public interface CouponRepository extends JpaRepository<Coupon, String> {
}
