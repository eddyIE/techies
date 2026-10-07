package vn.techies.ecommerce.loyalty.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import vn.techies.ecommerce.common.error.ApiException;
import vn.techies.ecommerce.common.error.ErrorCode;
import vn.techies.ecommerce.loyalty.AbstractPostgresTest;
import vn.techies.ecommerce.loyalty.api.dto.LoyaltyDtos.AwardRequest;
import vn.techies.ecommerce.loyalty.api.dto.LoyaltyDtos.ClaimResponse;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class GiftClaimTest extends AbstractPostgresTest {

    private static final UUID GIFT_TIER_0 = UUID.fromString("ce2eb2ca-a432-5d29-9755-1f4f1abf2a14");
    private static final UUID GIFT_CHEAP_TIER_0 = UUID.fromString("fa31e0b6-78d5-5739-a025-965512251dd0");
    private static final UUID GIFT_TIER_2 = UUID.fromString("f0fb47a7-58e4-576e-9470-3b91dc212271");
    private static final UUID GIFT_OUT_OF_STOCK = UUID.fromString("e20b380a-f62c-56eb-9ba4-f7cbf404ee5c");
    private static final UUID GIFT_UNAFFORDABLE = UUID.fromString("378bdc0f-8cea-57b3-a646-86b07ac39296");

    private static final AtomicInteger UNIQUE = new AtomicInteger();

    @Autowired
    private LoyaltyService loyalty;
    @Autowired
    private JdbcTemplate jdbc;

    private UUID withPoints(String amountSpent) {
        UUID userId = UUID.randomUUID();
        loyalty.award(new AwardRequest("TK-F5-" + UNIQUE.incrementAndGet(), userId,
                new BigDecimal(amountSpent)));
        return userId;
    }

    private long balanceOf(UUID userId) {
        Long value = jdbc.queryForObject("SELECT COALESCE(SUM(points), 0) FROM points_ledger "
                + "WHERE user_id = ?", Long.class, userId);
        return value == null ? 0 : value;
    }

    private long lifetimeOf(UUID userId) {
        Long value = jdbc.queryForObject("SELECT COALESCE(SUM(points), 0) FROM points_ledger "
                + "WHERE user_id = ? AND points > 0", Long.class, userId);
        return value == null ? 0 : value;
    }

    private int stockOf(UUID giftId) {
        Integer value = jdbc.queryForObject("SELECT stock FROM gifts WHERE id = ?", Integer.class, giftId);
        return value == null ? 0 : value;
    }

    private int ledgerRows(UUID userId) {
        Integer value = jdbc.queryForObject("SELECT COUNT(*) FROM points_ledger WHERE user_id = ? "
                + "AND entry_type = 'GIFT_SPEND'", Integer.class, userId);
        return value == null ? 0 : value;
    }

    /** A private gift of its own, so a concurrency test cannot be skewed by the shared catalogue. */
    private UUID giftWithStock(int stock, int pointsCost) {
        UUID giftId = UUID.randomUUID();
        jdbc.update("INSERT INTO gifts (id, name, description, image_url, points_cost, min_tier, "
                + "stock, active) VALUES (?, 'Quà test', 'x', 'x', ?, 0, ?, TRUE)",
                giftId, pointsCost, stock);
        return giftId;
    }

    @Test
    @DisplayName("A claim hands back a code the customer reads at the counter")
    void returnsACode() {
        ClaimResponse claim = loyalty.claim(withPoints("10000000"), GIFT_TIER_0);

        assertThat(claim.code()).matches("GIFT-[2-9A-HJKMNP-Z]{4}-[2-9A-HJKMNP-Z]{4}");
    }

    @Test
    @DisplayName("Claiming spends the gift's cost")
    void spendsThePoints() {
        UUID userId = withPoints("10000000");

        loyalty.claim(userId, GIFT_TIER_0);

        assertThat(balanceOf(userId)).isEqualTo(10_000 - 500);
    }

    @Test
    @DisplayName("Claiming never touches the lifetime total, so it cannot demote anyone")
    void leavesLifetimePointsAlone() {
        UUID userId = withPoints("10000000");

        loyalty.claim(userId, GIFT_TIER_0);

        assertThat(lifetimeOf(userId)).isEqualTo(10_000);
    }

    @Test
    @DisplayName("Claiming reports the balance the app should now show")
    void reportsTheBalanceAfter() {
        assertThat(loyalty.claim(withPoints("10000000"), GIFT_TIER_0).balanceAfter())
                .isEqualTo(9_500);
    }

    @Test
    @DisplayName("Claiming takes one off the gift's stock")
    void movesStockOnce() {
        UUID giftId = giftWithStock(5, 500);

        loyalty.claim(withPoints("10000000"), giftId);

        assertThat(stockOf(giftId)).isEqualTo(4);
    }

    @Test
    @DisplayName("The gift name is snapshotted, so renaming it later cannot rewrite history")
    void snapshotsTheGiftName() {
        assertThat(loyalty.claim(withPoints("10000000"), GIFT_TIER_0).giftName())
                .isEqualTo("Ốp lưng silicon");
    }

    @Test
    @DisplayName("Claiming the same gift twice is refused")
    void refusesAReClaim() {
        UUID userId = withPoints("10000000");
        loyalty.claim(userId, GIFT_TIER_0);

        assertThatThrownBy(() -> loyalty.claim(userId, GIFT_TIER_0))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.GIFT_ALREADY_CLAIMED);
    }

    @Test
    @DisplayName("A refused re-claim spends the points only once")
    void spendsOnceAcrossAReClaim() {
        UUID userId = withPoints("10000000");
        loyalty.claim(userId, GIFT_TIER_0);

        assertThatThrownBy(() -> loyalty.claim(userId, GIFT_TIER_0)).isInstanceOf(ApiException.class);

        assertThat(balanceOf(userId)).isEqualTo(9_500);
    }

    @Test
    @DisplayName("A refused re-claim rolls the stock decrement back")
    void movesStockOnceAcrossAReClaim() {
        UUID giftId = giftWithStock(5, 500);
        UUID userId = withPoints("10000000");
        loyalty.claim(userId, giftId);

        assertThatThrownBy(() -> loyalty.claim(userId, giftId)).isInstanceOf(ApiException.class);

        assertThat(stockOf(giftId)).isEqualTo(4);
    }

    @Test
    @DisplayName("A gift above the customer's tier is refused")
    void refusesAGiftAboveTheTier() {
        assertThatThrownBy(() -> loyalty.claim(withPoints("10000000"), GIFT_TIER_2))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.TIER_TOO_LOW);
    }

    @Test
    @DisplayName("A refused tier check writes nothing")
    void writesNothingWhenTheTierIsTooLow() {
        UUID userId = withPoints("10000000");

        assertThatThrownBy(() -> loyalty.claim(userId, GIFT_TIER_2)).isInstanceOf(ApiException.class);

        assertThat(ledgerRows(userId)).isZero();
    }

    @Test
    @DisplayName("A gift costing more than the balance is refused")
    void refusesAGiftBeyondTheBalance() {
        assertThatThrownBy(() -> loyalty.claim(withPoints("10000000"), GIFT_UNAFFORDABLE))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.INSUFFICIENT_POINTS);
    }

    @Test
    @DisplayName("A gift with no stock left is refused")
    void refusesAnOutOfStockGift() {
        assertThatThrownBy(() -> loyalty.claim(withPoints("10000000"), GIFT_OUT_OF_STOCK))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.GIFT_OUT_OF_STOCK);
    }

    @Test
    @DisplayName("A refused out-of-stock claim writes no ledger row")
    void writesNothingWhenOutOfStock() {
        UUID userId = withPoints("10000000");

        assertThatThrownBy(() -> loyalty.claim(userId, GIFT_OUT_OF_STOCK))
                .isInstanceOf(ApiException.class);

        assertThat(ledgerRows(userId)).isZero();
    }

    @Test
    @DisplayName("An unknown gift is a 404, not a 409")
    void refusesAnUnknownGift() {
        assertThatThrownBy(() -> loyalty.claim(withPoints("10000000"), UUID.randomUUID()))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.GIFT_NOT_FOUND);
    }

    @Test
    @DisplayName("An inactive gift is invisible to claim as well as to the catalogue")
    void refusesAnInactiveGift() {
        UUID giftId = UUID.randomUUID();
        jdbc.update("INSERT INTO gifts (id, name, description, image_url, points_cost, min_tier, "
                + "stock, active) VALUES (?, 'Đã ẩn', 'x', 'x', 100, 0, 5, FALSE)", giftId);

        assertThatThrownBy(() -> loyalty.claim(withPoints("10000000"), giftId))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.GIFT_NOT_FOUND);
    }

    @Test
    @DisplayName("Two codes generated back to back are not sequential")
    void generatesUnguessableCodes() {
        String first = loyalty.claim(withPoints("10000000"), GIFT_TIER_0).code();
        String second = loyalty.claim(withPoints("10000000"), GIFT_TIER_0).code();

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    @DisplayName("A claimed gift keeps its code on the claimed-gifts screen")
    void keepsTheCodeReadable() {
        UUID userId = withPoints("10000000");
        String code = loyalty.claim(userId, GIFT_TIER_0).code();

        assertThat(loyalty.claimedGiftsFor(userId)).singleElement()
                .extracting("code").isEqualTo(code);
    }

    @Test
    @DisplayName("The claimed-gifts screen carries the image, which is read live rather than snapshotted")
    void carriesTheGiftImage() {
        UUID userId = withPoints("10000000");
        loyalty.claim(userId, GIFT_TIER_0);

        assertThat(loyalty.claimedGiftsFor(userId)).singleElement()
                .extracting("imageUrl").isEqualTo("https://picsum.photos/seed/op-lung-silicon/400");
    }

    @Test
    @DisplayName("The newest claim is first, so the screen opens on what was just claimed")
    void ordersTheNewestFirst() {
        UUID userId = withPoints("10000000");
        loyalty.claim(userId, GIFT_TIER_0);
        loyalty.claim(userId, GIFT_CHEAP_TIER_0);

        assertThat(loyalty.claimedGiftsFor(userId)).first()
                .extracting("giftName").isEqualTo("Cáp sạc USB-C 1m");
    }

    @Test
    @DisplayName("Ten customers racing for one unit produce exactly one winner")
    void letsOnlyOneWinTheLastUnit() throws Exception {
        assertThat(raceForOneUnit().winners()).isEqualTo(1);
    }

    @Test
    @DisplayName("The race leaves the gift at zero stock, never negative")
    void leavesStockAtZeroAfterTheRace() throws Exception {
        assertThat(raceForOneUnit().remainingStock()).isZero();
    }

    private record RaceResult(int winners, int remainingStock) {
    }

    /**
     * Ten users, one unit. The conditional UPDATE is the guard: nine of these see 0 rows
     * updated and roll back, exactly as inventory's deduct does for the last item of stock.
     */
    private RaceResult raceForOneUnit() throws Exception {
        UUID giftId = giftWithStock(1, 500);
        List<UUID> users = java.util.stream.IntStream.range(0, 10)
                .mapToObj(i -> withPoints("10000000"))
                .toList();

        CountDownLatch start = new CountDownLatch(1);
        List<Callable<Boolean>> attempts = users.stream().map(userId -> (Callable<Boolean>) () -> {
            start.await();
            try {
                loyalty.claim(userId, giftId);
                return true;
            } catch (ApiException ex) {
                return false;
            }
        }).toList();

        ExecutorService pool = Executors.newFixedThreadPool(10);
        try {
            List<Future<Boolean>> futures = attempts.stream().map(pool::submit).toList();
            start.countDown();
            pool.shutdown();
            pool.awaitTermination(30, TimeUnit.SECONDS);

            int winners = 0;
            for (Future<Boolean> future : futures) {
                if (future.get()) {
                    winners++;
                }
            }
            return new RaceResult(winners, stockOf(giftId));
        } finally {
            pool.shutdownNow();
        }
    }
}
