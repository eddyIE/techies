package vn.techies.ecommerce.loyalty.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import vn.techies.ecommerce.loyalty.AbstractPostgresTest;
import vn.techies.ecommerce.loyalty.api.dto.LoyaltyDtos.AwardRequest;
import vn.techies.ecommerce.loyalty.api.dto.LoyaltyDtos.AwardResponse;
import vn.techies.ecommerce.loyalty.api.dto.LoyaltyDtos.VoucherSummary;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * `amountSpent` is already `subtotal - discount` when it arrives: excluding shipping and
 * applying the coupon are order-service's job, verified there (tasks/todo.md, G1).
 */
@SpringBootTest
class PointsAwardTest extends AbstractPostgresTest {

    @Autowired
    private LoyaltyService loyalty;
    @Autowired
    private JdbcTemplate jdbc;

    private static AwardRequest spend(UUID userId, String orderRef, String amount) {
        return new AwardRequest(orderRef, userId, new BigDecimal(amount));
    }

    private long balanceOf(UUID userId) {
        Long value = jdbc.queryForObject(
                "SELECT COALESCE(SUM(points), 0) FROM points_ledger WHERE user_id = ?",
                Long.class, userId);
        return value == null ? 0 : value;
    }

    @Test
    @DisplayName("10.000.000đ earns 10.000 points, one per thousand")
    void earnsOnePointPerThousandDong() {
        UUID userId = UUID.randomUUID();

        assertThat(loyalty.award(spend(userId, "TK-F3-RATE", "10000000")).points()).isEqualTo(10_000);
    }

    @Test
    @DisplayName("Points are floored, so a part-thousand never rounds into a tier")
    void floorsPartThousands() {
        UUID userId = UUID.randomUUID();

        assertThat(loyalty.award(spend(userId, "TK-F3-FLOOR", "10999500")).points()).isEqualTo(10_999);
    }

    @Test
    @DisplayName("A second award for the same order credits nothing")
    void creditsOncePerOrder() {
        UUID userId = UUID.randomUUID();
        loyalty.award(spend(userId, "TK-F3-REPLAY", "10000000"));

        loyalty.award(spend(userId, "TK-F3-REPLAY", "10000000"));

        assertThat(balanceOf(userId)).isEqualTo(10_000);
    }

    @Test
    @DisplayName("The replayed award reports the points the first one credited")
    void replayReportsTheOriginalPoints() {
        UUID userId = UUID.randomUUID();
        loyalty.award(spend(userId, "TK-F3-REPLAY-2", "10000000"));

        assertThat(loyalty.award(spend(userId, "TK-F3-REPLAY-2", "10000000")).points())
                .isEqualTo(10_000);
    }

    @Test
    @DisplayName("The replayed award says it awarded nothing")
    void replayIsNotAnAward() {
        UUID userId = UUID.randomUUID();
        loyalty.award(spend(userId, "TK-F3-REPLAY-3", "10000000"));

        assertThat(loyalty.award(spend(userId, "TK-F3-REPLAY-3", "10000000")).awarded()).isFalse();
    }

    @Test
    @DisplayName("An order that cost nothing writes no ledger row, since an earn must be positive")
    void writesNothingForAZeroSpend() {
        UUID userId = UUID.randomUUID();

        loyalty.award(spend(userId, "TK-F3-FREE", "900"));

        assertThat(balanceOf(userId)).isZero();
    }

    @Test
    @DisplayName("An account with no orders is tier 0")
    void startsAtTierZero() {
        assertThat(loyalty.award(spend(UUID.randomUUID(), "TK-F3-TINY", "500")).tier()).isZero();
    }

    @Test
    @DisplayName("10.000 lifetime points is tier 1")
    void reachesTierOne() {
        assertThat(loyalty.award(spend(UUID.randomUUID(), "TK-F3-T1", "10000000")).tier()).isEqualTo(1);
    }

    @Test
    @DisplayName("Crossing tier 1 issues exactly one voucher")
    void issuesOneVoucherPerRung() {
        AwardResponse response = loyalty.award(spend(UUID.randomUUID(), "TK-F3-V1", "10000000"));

        assertThat(response.vouchersIssued()).hasSize(1);
    }

    @Test
    @DisplayName("Tier 1's voucher is worth 10 percent")
    void issuesTierOneAtTenPercent() {
        AwardResponse response = loyalty.award(spend(UUID.randomUUID(), "TK-F3-V1-PCT", "10000000"));

        assertThat(response.vouchersIssued()).singleElement()
                .extracting(VoucherSummary::discountPercent).isEqualTo(10);
    }

    @Test
    @DisplayName("Replaying the award that crossed tier 1 issues no second voucher")
    void replayIssuesNoSecondVoucher() {
        UUID userId = UUID.randomUUID();
        loyalty.award(spend(userId, "TK-F3-V1-REPLAY", "10000000"));

        assertThat(loyalty.award(spend(userId, "TK-F3-V1-REPLAY", "10000000")).vouchersIssued())
                .isEmpty();
    }

    @Test
    @DisplayName("One order crossing tiers 1 and 2 together issues both vouchers")
    void issuesEveryRungOneOrderCrosses() {
        AwardResponse response = loyalty.award(spend(UUID.randomUUID(), "TK-F3-V12", "30000000"));

        assertThat(response.vouchersIssued()).extracting(VoucherSummary::tier)
                .containsExactly(1, 2);
    }

    @Test
    @DisplayName("Two awards reaching tier 2 in steps issue one voucher each")
    void issuesTheSecondRungOnTheSecondOrder() {
        UUID userId = UUID.randomUUID();
        loyalty.award(spend(userId, "TK-F3-STEP-1", "10000000"));

        assertThat(loyalty.award(spend(userId, "TK-F3-STEP-2", "20000000")).vouchersIssued())
                .extracting(VoucherSummary::tier).containsExactly(2);
    }

    @Test
    @DisplayName("Voucher codes are not sequential: two accounts crossing tier 1 differ")
    void issuesUnguessableCodes() {
        String first = loyalty.award(spend(UUID.randomUUID(), "TK-F3-CODE-1", "10000000"))
                .vouchersIssued().getFirst().code();
        String second = loyalty.award(spend(UUID.randomUUID(), "TK-F3-CODE-2", "10000000"))
                .vouchersIssued().getFirst().code();

        assertThat(first).isNotEqualTo(second);
    }
}
