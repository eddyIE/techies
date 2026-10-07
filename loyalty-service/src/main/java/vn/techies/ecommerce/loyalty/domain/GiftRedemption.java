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
 * A claimed gift. Terminal by design: there is no status and no collection step, because
 * nothing in this system could ever set one. The code stays readable indefinitely and what
 * happens at the counter is outside the app (SPEC-loyalty.md, A redemption is terminal).
 */
@Entity
@Table(name = "gift_redemptions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class GiftRedemption {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "gift_id", nullable = false)
    private UUID giftId;

    @Column(nullable = false, length = 20)
    private String code;

    /** Snapshot, like an order line's product name: renaming a gift must not rewrite history. */
    @Column(name = "gift_name", nullable = false, length = 200)
    private String giftName;

    @Column(name = "points_spent", nullable = false)
    private int pointsSpent;

    @Column(name = "claimed_at", nullable = false)
    private Instant claimedAt;

    public static GiftRedemption of(UUID userId, Gift gift, String code) {
        GiftRedemption redemption = new GiftRedemption();
        redemption.id = UUID.randomUUID();
        redemption.userId = userId;
        redemption.giftId = gift.getId();
        redemption.code = code;
        redemption.giftName = gift.getName();
        redemption.pointsSpent = gift.getPointsCost();
        redemption.claimedAt = Instant.now();
        return redemption;
    }
}
