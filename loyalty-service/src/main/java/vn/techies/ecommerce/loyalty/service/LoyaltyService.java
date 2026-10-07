package vn.techies.ecommerce.loyalty.service;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.techies.ecommerce.loyalty.api.dto.LoyaltyDtos.AwardRequest;
import vn.techies.ecommerce.loyalty.api.dto.LoyaltyDtos.AwardResponse;
import vn.techies.ecommerce.loyalty.api.dto.LoyaltyDtos.VoucherSummary;
import vn.techies.ecommerce.loyalty.domain.EntryType;
import vn.techies.ecommerce.loyalty.domain.PointsEntry;
import vn.techies.ecommerce.loyalty.domain.Tier;
import vn.techies.ecommerce.loyalty.domain.TierVoucher;
import vn.techies.ecommerce.loyalty.repository.PointsEntryRepository;
import vn.techies.ecommerce.loyalty.repository.TierRepository;
import vn.techies.ecommerce.loyalty.repository.TierVoucherRepository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class LoyaltyService {

    private static final Logger log = LoggerFactory.getLogger(LoyaltyService.class);

    /** 1 point per 1.000đ, one way only: points never convert back into a discount. */
    private static final BigDecimal DONG_PER_POINT = BigDecimal.valueOf(1000);

    private final PointsEntryRepository ledger;
    private final TierRepository tiers;
    private final TierVoucherRepository vouchers;
    private final CodeGenerator codes;

    /**
     * Credits a completed order and issues any voucher the new lifetime total unlocks.
     *
     * <p>Idempotent on the order ref: the UNIQUE on {@code (entry_type, reference)} is the
     * guarantee, and the lookup here is what turns a collision into a 200 rather than a 500.
     * A retried call reports the points the first one credited and mints nothing.
     */
    @Transactional
    public AwardResponse award(AwardRequest request) {
        var existing = ledger.findByEntryTypeAndReference(EntryType.ORDER_EARN, request.orderRef());
        int points = pointsFor(request.amountSpent());
        boolean awarded = false;

        if (existing.isPresent()) {
            points = existing.get().getPoints();
            log.info("Order {} already earned {} points; crediting nothing", request.orderRef(), points);
        } else if (points > 0) {
            // flushed so the lifetime SUM below sees this row
            ledger.saveAndFlush(PointsEntry.earn(request.userId(), points, request.orderRef()));
            awarded = true;
            log.info("Order {} earned {} points for user {}", request.orderRef(), points, request.userId());
        }

        int tier = tierFor(ledger.lifetimeOf(request.userId()));
        return new AwardResponse(awarded, points, tier, issueMissingVouchers(request.userId(), tier));
    }

    /**
     * {@code floor((subtotal - discount) / 1000)}. Floored rather than rounded, so 10.999.500đ
     * earns 10.999 points and never rounds up into a tier the customer did not pay for.
     */
    private static int pointsFor(BigDecimal amountSpent) {
        return amountSpent.divide(DONG_PER_POINT, 0, RoundingMode.FLOOR).intValue();
    }

    /** The highest rung the lifetime total has reached, or 0 below the first one. */
    private int tierFor(long lifetimePoints) {
        int tier = 0;
        for (Tier rung : tiers.findAllByOrderByTierAsc()) {
            if (lifetimePoints >= rung.getThresholdPoints()) {
                tier = rung.getTier();
            }
        }
        return tier;
    }

    /**
     * Issues a voucher for every rung at or below the current tier that has none yet.
     *
     * <p>Written as "what is missing" rather than "what this order crossed" for two reasons: a
     * single large order can cross two rungs at once and must grant both, and an award that
     * failed after its ledger row heals on the next one instead of silently owing a voucher.
     * The UNIQUE on {@code (user_id, tier)} is what makes repeating it safe.
     */
    private List<VoucherSummary> issueMissingVouchers(UUID userId, int tier) {
        List<VoucherSummary> issued = new ArrayList<>();
        for (Tier rung : tiers.findAllByOrderByTierAsc()) {
            if (rung.getTier() > tier || vouchers.existsByUserIdAndTier(userId, rung.getTier())) {
                continue;
            }
            TierVoucher voucher = vouchers.save(
                    TierVoucher.issue(userId, rung, codes.voucherCode(rung.getTier())));
            issued.add(summarise(voucher));
            log.info("Issued tier {} voucher {} ({}% off) to user {}",
                    rung.getTier(), voucher.getCode(), voucher.getDiscountPercent(), userId);
        }
        return issued;
    }

    static VoucherSummary summarise(TierVoucher voucher) {
        return new VoucherSummary(voucher.getCode(), voucher.getTier(), voucher.getDiscountPercent(),
                voucher.getIssuedAt(), voucher.getExpiresAt(), voucher.getConsumedAt());
    }
}
