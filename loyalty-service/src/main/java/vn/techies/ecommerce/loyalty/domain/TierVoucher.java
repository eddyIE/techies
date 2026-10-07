package vn.techies.ecommerce.loyalty.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * The reward for reaching a tier: a per-customer, single-use percentage off one cart. Coupons
 * cannot express that, which is why this is its own table (SPEC-loyalty.md, Vouchers are not
 * coupons).
 */
@Entity
@Table(name = "tier_vouchers")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TierVoucher {

    @Id
    @Column(length = 32)
    private String code;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(nullable = false)
    private short tier;

    @Column(name = "discount_percent", nullable = false)
    private short discountPercent;

    @Column(name = "issued_at", nullable = false)
    private Instant issuedAt;

    /** NULL means never, matching orders.coupons. Tier vouchers are issued without an expiry. */
    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "consumed_order_ref", length = 20)
    private String consumedOrderRef;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    public static TierVoucher issue(UUID userId, Tier rung, String code) {
        TierVoucher voucher = new TierVoucher();
        voucher.code = code;
        voucher.userId = userId;
        voucher.tier = rung.getTier();
        voucher.discountPercent = rung.getVoucherDiscountPercent();
        voucher.issuedAt = Instant.now();
        return voucher;
    }
}
