package vn.techies.ecommerce.loyalty.api.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class LoyaltyDtos {

    private LoyaltyDtos() {
    }

    /**
     * {@code amountSpent} is already {@code subtotal - discount}: order-service excludes
     * shipping and applies the coupon before calling, because only it knows either.
     */
    public record AwardRequest(
            @NotBlank @Size(max = 32) String orderRef,
            @NotNull UUID userId,
            @NotNull @DecimalMin("0") BigDecimal amountSpent) {
    }

    /**
     * {@code awarded} is false for a replayed order ref, where {@code points} still reports what
     * the first call credited. {@code vouchersIssued} lists what *this* call minted, so it is
     * empty on a replay.
     */
    public record AwardResponse(boolean awarded, int points, int tier, List<VoucherSummary> vouchersIssued) {
    }

    /**
     * {@code pointsToNextTier} is null at the top of the ladder, which is how the app knows to
     * stop showing progress rather than showing a bar that can never fill.
     */
    public record MeResponse(
            long lifetimePoints,
            long balance,
            int tier,
            Integer pointsToNextTier,
            List<TierRung> tiers) {
    }

    /** The ladder as data, so the app never hardcodes a threshold or names a tier. */
    public record TierRung(int tier, int thresholdPoints, int discountPercent) {
    }

    /**
     * {@code eligible} is the whole claim predicate answered in advance, so the app can grey a
     * card out; {@code inStock} and {@code alreadyClaimed} are there to explain why.
     */
    public record GiftResponse(
            UUID id,
            String name,
            String description,
            String imageUrl,
            int pointsCost,
            int minTier,
            boolean inStock,
            boolean eligible,
            boolean alreadyClaimed) {
    }

    public record VoucherSummary(
            String code,
            int tier,
            int discountPercent,
            Instant issuedAt,
            Instant expiresAt,
            Instant consumedAt) {
    }
}
