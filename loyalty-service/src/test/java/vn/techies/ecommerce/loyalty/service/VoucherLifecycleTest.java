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
import vn.techies.ecommerce.loyalty.api.dto.LoyaltyDtos.ConsumeRequest;
import vn.techies.ecommerce.loyalty.api.dto.LoyaltyDtos.ReleaseRequest;

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

/**
 * Consume and release mirror inventory's deduct and restore, for the same reason: checkout has
 * to claim the voucher before payment so two carts cannot both spend it, and give it back on
 * every path that unwinds.
 */
@SpringBootTest
class VoucherLifecycleTest extends AbstractPostgresTest {

    private static final BigDecimal ONE_MILLION = new BigDecimal("1000000.00");
    private static final AtomicInteger UNIQUE = new AtomicInteger();

    @Autowired
    private LoyaltyService loyalty;
    @Autowired
    private JdbcTemplate jdbc;

    private static String orderRef() {
        return "TK-F6-" + UNIQUE.incrementAndGet();
    }

    /** Climbs far enough to hold the tier's voucher, then hands back its code. */
    private String voucherAt(int tier, UUID userId) {
        String amount = switch (tier) {
            case 1 -> "10000000";
            case 2 -> "30000000";
            default -> "60000000";
        };
        return loyalty.award(new AwardRequest(orderRef(), userId, new BigDecimal(amount)))
                .vouchersIssued().stream()
                .filter(voucher -> voucher.tier() == tier)
                .findFirst()
                .orElseThrow()
                .code();
    }

    private String consumedRefOf(String code) {
        return jdbc.queryForObject("SELECT consumed_order_ref FROM tier_vouchers WHERE code = ?",
                String.class, code);
    }

    @Test
    @DisplayName("A tier 1 voucher takes 10 percent off the cart")
    void discountsByTheTierPercentage() {
        UUID userId = UUID.randomUUID();
        String code = voucherAt(1, userId);

        assertThat(loyalty.consume(code, new ConsumeRequest(userId, orderRef(), ONE_MILLION)).discount())
                .isEqualByComparingTo("100000");
    }

    @Test
    @DisplayName("A tier 3 voucher takes half off")
    void discountsByHalfAtTierThree() {
        UUID userId = UUID.randomUUID();
        String code = voucherAt(3, userId);

        assertThat(loyalty.consume(code, new ConsumeRequest(userId, orderRef(), ONE_MILLION)).discount())
                .isEqualByComparingTo("500000");
    }

    @Test
    @DisplayName("The discount is floored, so it never exceeds the subtotal by a fraction")
    void floorsTheDiscount() {
        UUID userId = UUID.randomUUID();
        String code = voucherAt(1, userId);

        assertThat(loyalty.consume(code,
                new ConsumeRequest(userId, orderRef(), new BigDecimal("10999999.00"))).discount())
                .isEqualByComparingTo("1099999");
    }

    @Test
    @DisplayName("Consuming records the order that spent it")
    void recordsTheConsumingOrder() {
        UUID userId = UUID.randomUUID();
        String code = voucherAt(1, userId);
        String ref = orderRef();

        loyalty.consume(code, new ConsumeRequest(userId, ref, ONE_MILLION));

        assertThat(consumedRefOf(code)).isEqualTo(ref);
    }

    @Test
    @DisplayName("A retried consume for the same order returns the same discount rather than refusing")
    void isIdempotentOnTheOrderRef() {
        UUID userId = UUID.randomUUID();
        String code = voucherAt(1, userId);
        String ref = orderRef();
        loyalty.consume(code, new ConsumeRequest(userId, ref, ONE_MILLION));

        assertThat(loyalty.consume(code, new ConsumeRequest(userId, ref, ONE_MILLION)).discount())
                .isEqualByComparingTo("100000");
    }

    @Test
    @DisplayName("A second order cannot spend a voucher already spent")
    void refusesASecondOrder() {
        UUID userId = UUID.randomUUID();
        String code = voucherAt(1, userId);
        loyalty.consume(code, new ConsumeRequest(userId, orderRef(), ONE_MILLION));

        assertThatThrownBy(() -> loyalty.consume(code,
                new ConsumeRequest(userId, orderRef(), ONE_MILLION)))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.VOUCHER_ALREADY_CONSUMED);
    }

    @Test
    @DisplayName("A voucher belongs to the customer it was issued to")
    void refusesAnotherCustomer() {
        String code = voucherAt(1, UUID.randomUUID());

        assertThatThrownBy(() -> loyalty.consume(code,
                new ConsumeRequest(UUID.randomUUID(), orderRef(), ONE_MILLION)))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.VOUCHER_NOT_OWNED);
    }

    @Test
    @DisplayName("An expired voucher is refused")
    void refusesAnExpiredVoucher() {
        UUID userId = UUID.randomUUID();
        jdbc.update("INSERT INTO tier_vouchers (code, user_id, tier, discount_percent, issued_at, "
                + "expires_at) VALUES (?, ?, 1, 10, NOW(), NOW() - INTERVAL '1 day')",
                "EXPIRED-" + UNIQUE.incrementAndGet(), userId);
        String code = jdbc.queryForObject("SELECT code FROM tier_vouchers WHERE user_id = ?",
                String.class, userId);

        assertThatThrownBy(() -> loyalty.consume(code,
                new ConsumeRequest(userId, orderRef(), ONE_MILLION)))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.VOUCHER_EXPIRED);
    }

    @Test
    @DisplayName("An unknown code is a 404, so checkout can tell it apart from a refusal")
    void refusesAnUnknownCode() {
        assertThatThrownBy(() -> loyalty.consume("TIER1-NOSUCH",
                new ConsumeRequest(UUID.randomUUID(), orderRef(), ONE_MILLION)))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.VOUCHER_NOT_FOUND);
    }

    @Test
    @DisplayName("Releasing returns the voucher to unconsumed")
    void releaseClearsTheConsumption() {
        UUID userId = UUID.randomUUID();
        String code = voucherAt(1, userId);
        String ref = orderRef();
        loyalty.consume(code, new ConsumeRequest(userId, ref, ONE_MILLION));

        loyalty.release(code, new ReleaseRequest(ref));

        assertThat(consumedRefOf(code)).isNull();
    }

    @Test
    @DisplayName("A released voucher can be spent again on the next attempt")
    void releaseMakesItSpendableAgain() {
        UUID userId = UUID.randomUUID();
        String code = voucherAt(1, userId);
        String ref = orderRef();
        loyalty.consume(code, new ConsumeRequest(userId, ref, ONE_MILLION));
        loyalty.release(code, new ReleaseRequest(ref));

        assertThat(loyalty.consume(code, new ConsumeRequest(userId, orderRef(), ONE_MILLION))
                .discount()).isEqualByComparingTo("100000");
    }

    @Test
    @DisplayName("Releasing a voucher nobody spent would invent one, so it is refused")
    void refusesToReleaseAnUnspentVoucher() {
        String code = voucherAt(1, UUID.randomUUID());

        assertThatThrownBy(() -> loyalty.release(code, new ReleaseRequest(orderRef())))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.NOTHING_TO_RELEASE);
    }

    @Test
    @DisplayName("One order cannot release a voucher another order is holding")
    void refusesToReleaseAnotherOrdersHold() {
        UUID userId = UUID.randomUUID();
        String code = voucherAt(1, userId);
        loyalty.consume(code, new ConsumeRequest(userId, orderRef(), ONE_MILLION));

        assertThatThrownBy(() -> loyalty.release(code, new ReleaseRequest(orderRef())))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.NOTHING_TO_RELEASE);
    }

    @Test
    @DisplayName("Two checkouts racing for one voucher produce exactly one winner")
    void letsOnlyOneCheckoutSpendIt() throws Exception {
        UUID userId = UUID.randomUUID();
        String code = voucherAt(1, userId);

        CountDownLatch start = new CountDownLatch(1);
        List<Callable<Boolean>> attempts = List.of(attempt(start, code, userId), attempt(start, code, userId));

        ExecutorService pool = Executors.newFixedThreadPool(2);
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
            assertThat(winners).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    private Callable<Boolean> attempt(CountDownLatch start, String code, UUID userId) {
        return () -> {
            start.await();
            try {
                loyalty.consume(code, new ConsumeRequest(userId, orderRef(), ONE_MILLION));
                return true;
            } catch (ApiException ex) {
                return false;
            }
        };
    }
}
