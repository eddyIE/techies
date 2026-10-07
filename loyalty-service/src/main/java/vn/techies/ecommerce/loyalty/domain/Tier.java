package vn.techies.ecommerce.loyalty.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** One rung of the ladder. Tier 0 has no row: it is simply being below tier 1's threshold. */
@Entity
@Table(name = "tiers")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Tier {

    @Id
    private short tier;

    @Column(name = "threshold_points", nullable = false)
    private int thresholdPoints;

    @Column(name = "voucher_discount_percent", nullable = false)
    private short voucherDiscountPercent;
}
