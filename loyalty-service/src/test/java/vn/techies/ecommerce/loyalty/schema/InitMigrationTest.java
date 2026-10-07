package vn.techies.ecommerce.loyalty.schema;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import vn.techies.ecommerce.loyalty.AbstractPostgresTest;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The constraints are the design here, not an afterthought: re-claim, award idempotency and
 * one-voucher-per-tier are all enforced by a UNIQUE rather than by a read-then-write.
 */
@SpringBootTest
class InitMigrationTest extends AbstractPostgresTest {

    private static final String TIER_3_THRESHOLD =
            "(SELECT threshold_points FROM tiers WHERE tier = 3)";

    @Autowired
    private JdbcTemplate jdbc;

    private int count(String sql, Object... args) {
        Integer value = jdbc.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }

    private void earn(UUID userId, String orderRef, int points) {
        jdbc.update("INSERT INTO points_ledger (id, user_id, entry_type, points, reference, created_at) "
                + "VALUES (?, ?, 'ORDER_EARN', ?, ?, NOW())", UUID.randomUUID(), userId, points, orderRef);
    }

    @Test
    @DisplayName("The three tiers seed at 10000 / 30000 / 60000 points for 10 / 30 / 50 percent")
    void seedsTheLadder() {
        assertThat(jdbc.queryForObject("SELECT string_agg(tier || ':' || threshold_points || '@' "
                + "|| voucher_discount_percent, ' ' ORDER BY tier) FROM tiers", String.class))
                .isEqualTo("1:10000@10 2:30000@30 3:60000@50");
    }

    @Test
    @DisplayName("At least two gifts are claimable from a standing start at tier 0")
    void seedsGiftsForTierZero() {
        assertThat(count("SELECT COUNT(*) FROM gifts WHERE min_tier = 0 AND stock > 0 "
                + "AND points_cost <= (SELECT threshold_points FROM tiers WHERE tier = 1)"))
                .isGreaterThanOrEqualTo(2);
    }

    @Test
    @DisplayName("Every tier above 0 has a gift of its own, so TIER_TOO_LOW is demoable")
    void seedsAGiftPerTier() {
        assertThat(jdbc.queryForObject("SELECT string_agg(DISTINCT min_tier::text, ',' "
                + "ORDER BY min_tier::text) FROM gifts WHERE min_tier > 0 AND stock > 0",
                String.class))
                .isEqualTo("1,2,3");
    }

    @Test
    @DisplayName("One seeded gift is out of stock, so GIFT_OUT_OF_STOCK is reachable")
    void seedsAnOutOfStockGift() {
        assertThat(count("SELECT COUNT(*) FROM gifts WHERE stock = 0 AND min_tier = 0 "
                + "AND points_cost <= (SELECT threshold_points FROM tiers WHERE tier = 1)"))
                .isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("One seeded gift costs more than tier 3, so INSUFFICIENT_POINTS never runs out")
    void seedsAnUnaffordableGift() {
        assertThat(count("SELECT COUNT(*) FROM gifts WHERE min_tier = 0 "
                + "AND points_cost > " + TIER_3_THRESHOLD))
                .isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("ck_points_sign rejects a GIFT_SPEND that adds points")
    void rejectsASpendThatEarns() {
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO points_ledger (id, user_id, entry_type, points, reference, created_at) "
                        + "VALUES (?, ?, 'GIFT_SPEND', 500, ?, NOW())",
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID().toString()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("One earn per order ever: the same reference cannot credit twice")
    void rejectsADuplicateEarn() {
        UUID userId = UUID.randomUUID();
        earn(userId, "TK-DUP-1", 10000);

        assertThatThrownBy(() -> earn(userId, "TK-DUP-1", 10000))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("One claim per customer per gift: the re-claim rule is a UNIQUE")
    void rejectsAReClaim() {
        UUID userId = UUID.randomUUID();
        UUID giftId = UUID.fromString("ce2eb2ca-a432-5d29-9755-1f4f1abf2a14");
        jdbc.update("INSERT INTO gift_redemptions (id, user_id, gift_id, code, gift_name, "
                        + "points_spent, claimed_at) VALUES (?, ?, ?, 'GIFT-AAAA-BBBB', 'x', 500, NOW())",
                UUID.randomUUID(), userId, giftId);

        assertThatThrownBy(() -> jdbc.update("INSERT INTO gift_redemptions (id, user_id, gift_id, "
                        + "code, gift_name, points_spent, claimed_at) "
                        + "VALUES (?, ?, ?, 'GIFT-CCCC-DDDD', 'x', 500, NOW())",
                UUID.randomUUID(), userId, giftId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("One voucher per tier per customer, so a replayed award cannot mint a second")
    void rejectsASecondVoucherForOneTier() {
        UUID userId = UUID.randomUUID();
        jdbc.update("INSERT INTO tier_vouchers (code, user_id, tier, discount_percent, issued_at) "
                + "VALUES ('TIER1-FIRST', ?, 1, 10, NOW())", userId);

        assertThatThrownBy(() -> jdbc.update("INSERT INTO tier_vouchers (code, user_id, tier, "
                + "discount_percent, issued_at) VALUES ('TIER1-SECOND', ?, 1, 10, NOW())", userId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
