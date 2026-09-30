package vn.techies.ecommerce.order.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

/** A fixed-amount discount. One code per order; no stacking, no percentages. */
@Entity
@Table(name = "coupons")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Coupon {

    @Id
    @Column(length = 32)
    private String code;

    @Column(nullable = false, length = 200)
    private String description;

    @Column(name = "discount_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal discountAmount;

    /** Null means the coupon applies at any order value. */
    @Column(name = "min_order_total", precision = 19, scale = 2)
    private BigDecimal minOrderTotal;

    @Column(nullable = false)
    private boolean active;

    /** Null means it never expires. */
    @Column(name = "expires_at")
    private Instant expiresAt;

    public boolean isUsable() {
        return active && (expiresAt == null || expiresAt.isAfter(Instant.now()));
    }

    public boolean appliesTo(BigDecimal subtotal) {
        return minOrderTotal == null || subtotal.compareTo(minOrderTotal) >= 0;
    }

    /**
     * Never more than the subtotal. A coupon reduces what is owed; it does not pay the customer,
     * and the orders table refuses a discount larger than the subtotal anyway.
     */
    public BigDecimal discountFor(BigDecimal subtotal) {
        return discountAmount.min(subtotal);
    }
}
