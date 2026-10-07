package vn.techies.ecommerce.loyalty.service;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.techies.ecommerce.common.error.ApiException;
import vn.techies.ecommerce.common.error.ErrorCode;
import vn.techies.ecommerce.loyalty.api.dto.LoyaltyDtos.AwardRequest;
import vn.techies.ecommerce.loyalty.api.dto.LoyaltyDtos.AwardResponse;
import vn.techies.ecommerce.loyalty.api.dto.LoyaltyDtos.ClaimResponse;
import vn.techies.ecommerce.loyalty.api.dto.LoyaltyDtos.ConsumeRequest;
import vn.techies.ecommerce.loyalty.api.dto.LoyaltyDtos.ConsumeResponse;
import vn.techies.ecommerce.loyalty.api.dto.LoyaltyDtos.ReleaseRequest;
import vn.techies.ecommerce.loyalty.api.dto.LoyaltyDtos.ReleaseResponse;
import vn.techies.ecommerce.loyalty.api.dto.LoyaltyDtos.ClaimedGiftResponse;
import vn.techies.ecommerce.loyalty.api.dto.LoyaltyDtos.GiftResponse;
import vn.techies.ecommerce.loyalty.api.dto.LoyaltyDtos.MeResponse;
import vn.techies.ecommerce.loyalty.api.dto.LoyaltyDtos.TierRung;
import vn.techies.ecommerce.loyalty.api.dto.LoyaltyDtos.VoucherSummary;
import vn.techies.ecommerce.loyalty.domain.EntryType;
import vn.techies.ecommerce.loyalty.domain.Gift;
import vn.techies.ecommerce.loyalty.domain.GiftRedemption;
import vn.techies.ecommerce.loyalty.domain.PointsEntry;
import vn.techies.ecommerce.loyalty.domain.Tier;
import vn.techies.ecommerce.loyalty.domain.TierVoucher;
import vn.techies.ecommerce.loyalty.repository.GiftRedemptionRepository;
import vn.techies.ecommerce.loyalty.repository.GiftRepository;
import vn.techies.ecommerce.loyalty.repository.PointsEntryRepository;
import vn.techies.ecommerce.loyalty.repository.TierRepository;
import vn.techies.ecommerce.loyalty.repository.TierVoucherRepository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class LoyaltyService {

    private static final Logger log = LoggerFactory.getLogger(LoyaltyService.class);

    /** 1 point per 1.000đ, one way only: points never convert back into a discount. */
    private static final BigDecimal DONG_PER_POINT = BigDecimal.valueOf(1000);

    private static final BigDecimal ONE_HUNDRED = BigDecimal.valueOf(100);

    /** Named in V1__init.sql. Postgres puts it in the error, which is how a re-claim is told apart. */
    private static final String RE_CLAIM_CONSTRAINT = "ux_gift_redemptions_user_gift";

    private final PointsEntryRepository ledger;
    private final TierRepository tiers;
    private final TierVoucherRepository vouchers;
    private final GiftRepository gifts;
    private final GiftRedemptionRepository redemptions;
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

        List<Tier> ladder = tiers.findAllByOrderByTierAsc();
        int tier = tierFor(ladder, ledger.lifetimeOf(request.userId()));
        return new AwardResponse(awarded, points, tier,
                issueMissingVouchers(request.userId(), ladder, tier));
    }

    /** Points, tier and the ladder behind it, for the loyalty screen. */
    @Transactional(readOnly = true)
    public MeResponse summaryFor(UUID userId) {
        long lifetime = ledger.lifetimeOf(userId);
        List<Tier> ladder = tiers.findAllByOrderByTierAsc();
        Integer toNextTier = ladder.stream()
                .filter(rung -> rung.getThresholdPoints() > lifetime)
                .findFirst()
                .map(rung -> (int) (rung.getThresholdPoints() - lifetime))
                .orElse(null);

        return new MeResponse(lifetime, ledger.balanceOf(userId), tierFor(ladder, lifetime),
                toNextTier, ladder.stream().map(LoyaltyService::describe).toList());
    }

    /**
     * The exchange catalogue, answered for this customer: {@code eligible} already accounts for
     * tier, balance, stock and a previous claim, so the app does not re-implement the rules.
     */
    @Transactional(readOnly = true)
    public List<GiftResponse> giftsFor(UUID userId) {
        int tier = tierFor(tiers.findAllByOrderByTierAsc(), ledger.lifetimeOf(userId));
        long balance = ledger.balanceOf(userId);
        Set<UUID> claimed = redemptions.findGiftIdsByUserId(userId);

        return gifts.findByActiveTrueOrderByPointsCostAsc().stream()
                .map(gift -> describe(gift, tier, balance, claimed.contains(gift.getId())))
                .toList();
    }

    /**
     * The five-step local transaction from SPEC-loyalty.md. No saga: every table involved is in
     * this schema, so one rollback undoes the whole thing.
     *
     * <p>Order matters. Tier and balance are read first because they refuse without touching
     * anything; stock is taken before the redemption row so the conditional UPDATE settles a
     * race; and the UNIQUE on {@code (user_id, gift_id)} settles a re-claim, which is why no
     * read-then-write check is needed for it.
     */
    @Transactional
    public ClaimResponse claim(UUID userId, UUID giftId) {
        Gift gift = gifts.findByIdAndActiveTrue(giftId)
                .orElseThrow(() -> new ApiException(ErrorCode.GIFT_NOT_FOUND,
                        "No gift " + giftId + " in the catalogue"));

        int tier = tierFor(tiers.findAllByOrderByTierAsc(), ledger.lifetimeOf(userId));
        if (tier < gift.getMinTier()) {
            throw new ApiException(ErrorCode.TIER_TOO_LOW,
                    "Gift " + giftId + " needs tier " + gift.getMinTier() + ", customer is tier " + tier);
        }

        long balance = ledger.balanceOf(userId);
        if (balance < gift.getPointsCost()) {
            throw new ApiException(ErrorCode.INSUFFICIENT_POINTS,
                    "Gift " + giftId + " costs " + gift.getPointsCost() + ", balance is " + balance);
        }

        if (gifts.takeOneFromStock(giftId) == 0) {
            throw new ApiException(ErrorCode.GIFT_OUT_OF_STOCK, "Gift " + giftId + " is out of stock");
        }

        GiftRedemption redemption = saveRedemption(userId, gift);
        ledger.save(PointsEntry.spend(userId, gift.getPointsCost(), redemption.getId()));

        log.info("User {} claimed gift {} for {} points, code {}",
                userId, giftId, gift.getPointsCost(), redemption.getCode());
        return new ClaimResponse(redemption.getId(), redemption.getCode(), redemption.getGiftName(),
                redemption.getPointsSpent(), balance - gift.getPointsCost(), redemption.getClaimedAt());
    }

    /**
     * Inserts the redemption and turns the re-claim collision into a 409.
     *
     * <p>Matched on the constraint name rather than on any integrity violation, so a code
     * collision stays the 500 it deserves to be instead of being reported as a re-claim.
     * Throwing from here rolls back the stock already taken above.
     */
    private GiftRedemption saveRedemption(UUID userId, Gift gift) {
        try {
            return redemptions.saveAndFlush(
                    GiftRedemption.of(userId, gift, codes.giftCode()));
        } catch (DataIntegrityViolationException ex) {
            if (String.valueOf(ex.getMostSpecificCause().getMessage())
                    .contains(RE_CLAIM_CONSTRAINT)) {
                throw new ApiException(ErrorCode.GIFT_ALREADY_CLAIMED,
                        "User " + userId + " already claimed gift " + gift.getId(), ex);
            }
            throw ex;
        }
    }

    /** Newest first: the screen should open on what was just claimed. */
    @Transactional(readOnly = true)
    public List<ClaimedGiftResponse> claimedGiftsFor(UUID userId) {
        List<GiftRedemption> claimed = redemptions.findByUserIdOrderByClaimedAtDesc(userId);
        Map<UUID, String> images = gifts.findAllById(
                        claimed.stream().map(GiftRedemption::getGiftId).toList()).stream()
                .collect(Collectors.toMap(Gift::getId, Gift::getImageUrl));

        return claimed.stream()
                .map(redemption -> new ClaimedGiftResponse(redemption.getId(), redemption.getCode(),
                        redemption.getGiftName(), images.get(redemption.getGiftId()),
                        redemption.getPointsSpent(), redemption.getClaimedAt()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<VoucherSummary> vouchersFor(UUID userId) {
        return vouchers.findByUserIdOrderByTierAsc(userId).stream()
                .map(LoyaltyService::summarise)
                .toList();
    }

    /**
     * {@code floor((subtotal - discount) / 1000)}. Floored rather than rounded, so 10.999.500đ
     * earns 10.999 points and never rounds up into a tier the customer did not pay for.
     */
    private static int pointsFor(BigDecimal amountSpent) {
        return amountSpent.divide(DONG_PER_POINT, 0, RoundingMode.FLOOR).intValue();
    }

    /** The highest rung the lifetime total has reached, or 0 below the first one. */
    private static int tierFor(List<Tier> ladder, long lifetimePoints) {
        int tier = 0;
        for (Tier rung : ladder) {
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
    private List<VoucherSummary> issueMissingVouchers(UUID userId, List<Tier> ladder, int tier) {
        List<VoucherSummary> issued = new ArrayList<>();
        for (Tier rung : ladder) {
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

    /**
     * Claims the voucher for an order, before payment. The atomic UPDATE is the guard: two
     * checkouts submitting one code cannot both get a discount.
     *
     * <p>Idempotent on {@code (code, orderRef)}, so order-service retrying a timed-out call
     * gets the same discount back rather than a refusal for a voucher it already holds.
     */
    @Transactional
    public ConsumeResponse consume(String code, ConsumeRequest request) {
        TierVoucher voucher = vouchers.findById(code)
                .orElseThrow(() -> new ApiException(ErrorCode.VOUCHER_NOT_FOUND,
                        "No voucher " + code));

        if (!voucher.getUserId().equals(request.userId())) {
            throw new ApiException(ErrorCode.VOUCHER_NOT_OWNED,
                    "Voucher " + code + " belongs to another customer");
        }
        if (voucher.getExpiresAt() != null && voucher.getExpiresAt().isBefore(Instant.now())) {
            throw new ApiException(ErrorCode.VOUCHER_EXPIRED,
                    "Voucher " + code + " expired at " + voucher.getExpiresAt());
        }

        BigDecimal discount = discountOn(request.subtotal(), voucher.getDiscountPercent());

        if (request.orderRef().equals(voucher.getConsumedOrderRef())) {
            log.info("Voucher {} already consumed by order {}; returning the same discount",
                    code, request.orderRef());
            return new ConsumeResponse(code, discount);
        }
        if (vouchers.consumeIfUnspent(code, request.orderRef()) == 0) {
            throw new ApiException(ErrorCode.VOUCHER_ALREADY_CONSUMED,
                    "Voucher " + code + " was already spent on order " + voucher.getConsumedOrderRef());
        }

        log.info("Order {} consumed voucher {} for a discount of {}",
                request.orderRef(), code, discount);
        return new ConsumeResponse(code, discount);
    }

    /**
     * The compensating transaction, called on all three unwinding paths: stock failure, payment
     * failure, and cancelling a confirmed order. Releasing a voucher nobody spent would invent
     * one, so that is a refusal rather than a silent success, exactly as restoring stock is.
     */
    @Transactional
    public ReleaseResponse release(String code, ReleaseRequest request) {
        if (!vouchers.existsById(code)) {
            throw new ApiException(ErrorCode.VOUCHER_NOT_FOUND, "No voucher " + code);
        }
        if (vouchers.releaseIfHeldBy(code, request.orderRef()) == 0) {
            throw new ApiException(ErrorCode.NOTHING_TO_RELEASE,
                    "Voucher " + code + " is not held by order " + request.orderRef());
        }

        log.info("Released voucher {} from order {}", code, request.orderRef());
        return new ReleaseResponse(code, true);
    }

    /**
     * Floored to whole đồng. The result is snapshotted onto the order as its discount, where
     * ck_orders_discount_within_subtotal requires it not to exceed the subtotal.
     */
    private static BigDecimal discountOn(BigDecimal subtotal, int percent) {
        return subtotal.multiply(BigDecimal.valueOf(percent))
                .divide(ONE_HUNDRED, 0, RoundingMode.FLOOR);
    }

    private static GiftResponse describe(Gift gift, int tier, long balance, boolean alreadyClaimed) {
        boolean eligible = !alreadyClaimed
                && gift.isInStock()
                && tier >= gift.getMinTier()
                && balance >= gift.getPointsCost();

        return new GiftResponse(gift.getId(), gift.getName(), gift.getDescription(),
                gift.getImageUrl(), gift.getPointsCost(), gift.getMinTier(), gift.isInStock(),
                eligible, alreadyClaimed);
    }

    private static TierRung describe(Tier rung) {
        return new TierRung(rung.getTier(), rung.getThresholdPoints(), rung.getVoucherDiscountPercent());
    }

    static VoucherSummary summarise(TierVoucher voucher) {
        return new VoucherSummary(voucher.getCode(), voucher.getTier(), voucher.getDiscountPercent(),
                voucher.getIssuedAt(), voucher.getExpiresAt(), voucher.getConsumedAt());
    }
}
