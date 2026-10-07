package vn.techies.ecommerce.loyalty.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import vn.techies.ecommerce.common.security.UserPrincipal;
import vn.techies.ecommerce.loyalty.AbstractPostgresTest;
import vn.techies.ecommerce.loyalty.api.dto.LoyaltyDtos.AwardRequest;
import vn.techies.ecommerce.loyalty.service.LoyaltyService;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Every response states the tier as a number. Naming it is the app's decision, so the server
 * never ships "Đồng / Bạc / Vàng" and never has to be released to change them.
 */
@SpringBootTest
@AutoConfigureMockMvc
class LoyaltyReadApiTest extends AbstractPostgresTest {

    private static final String GIFT_TIER_0 = "ce2eb2ca-a432-5d29-9755-1f4f1abf2a14";
    private static final String GIFT_TIER_2 = "f0fb47a7-58e4-576e-9470-3b91dc212271";
    private static final String GIFT_OUT_OF_STOCK = "e20b380a-f62c-56eb-9ba4-f7cbf404ee5c";
    private static final String GIFT_UNAFFORDABLE = "378bdc0f-8cea-57b3-a646-86b07ac39296";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private LoyaltyService loyalty;
    @Autowired
    private JdbcTemplate jdbc;

    private static final AtomicInteger UNIQUE = new AtomicInteger();

    private UUID withPoints(String amountSpent) {
        UUID userId = UUID.randomUUID();
        String orderRef = "TK-F4-" + UNIQUE.incrementAndGet();
        loyalty.award(new AwardRequest(orderRef, userId, new BigDecimal(amountSpent)));
        return userId;
    }

    private org.springframework.test.web.servlet.ResultActions as(UUID userId, String path) throws Exception {
        return mvc.perform(get(path).header(UserPrincipal.HEADER_USER_ID, userId));
    }

    /** Unique and inside the column's 20 characters, which a bare UUID is not. */
    private static String claimCode() {
        return "GIFT-TEST-" + UNIQUE.incrementAndGet();
    }

    /** JsonPath filter for one gift's field inside the catalogue array. */
    private static String field(String giftId, String name) {
        return "$[?(@.id=='" + giftId + "')]." + name;
    }

    @Test
    @DisplayName("A fresh account is tier 0")
    void startsAtTierZero() throws Exception {
        as(UUID.randomUUID(), "/loyalty/me").andExpect(jsonPath("$.tier").value(0));
    }

    @Test
    @DisplayName("A fresh account has no balance")
    void startsWithNoBalance() throws Exception {
        as(UUID.randomUUID(), "/loyalty/me").andExpect(jsonPath("$.balance").value(0));
    }

    @Test
    @DisplayName("A fresh account has earned nothing in its lifetime")
    void startsWithNoLifetimePoints() throws Exception {
        as(UUID.randomUUID(), "/loyalty/me").andExpect(jsonPath("$.lifetimePoints").value(0));
    }

    @Test
    @DisplayName("A fresh account needs tier 1's full threshold to climb")
    void reportsTheGapToTierOne() throws Exception {
        as(UUID.randomUUID(), "/loyalty/me").andExpect(jsonPath("$.pointsToNextTier").value(10000));
    }

    @Test
    @DisplayName("`me` hands the app the whole ladder so it never hardcodes a threshold")
    void returnsTheLadder() throws Exception {
        as(UUID.randomUUID(), "/loyalty/me").andExpect(jsonPath("$.tiers", hasSize(3)));
    }

    @Test
    @DisplayName("The top of the ladder has no next tier")
    void reportsNoGapAtTierThree() throws Exception {
        as(withPoints("60000000"), "/loyalty/me")
                .andExpect(jsonPath("$.pointsToNextTier").doesNotExist());
    }

    @Test
    @DisplayName("The tier is never named, only numbered")
    void neverNamesTheTier() throws Exception {
        as(withPoints("10000000"), "/loyalty/me")
                .andExpect(content().string(not(containsString("tierName"))));
    }

    @Test
    @DisplayName("A fresh account holds no vouchers")
    void startsWithNoVouchers() throws Exception {
        as(UUID.randomUUID(), "/loyalty/vouchers").andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    @DisplayName("Crossing tier 1 puts a 10 percent voucher in the wallet")
    void listsTheTierOneVoucher() throws Exception {
        as(withPoints("10000000"), "/loyalty/vouchers")
                .andExpect(jsonPath("$[0].discountPercent").value(10));
    }

    @Test
    @DisplayName("An inactive gift is not in the catalogue")
    void hidesInactiveGifts() throws Exception {
        UUID hidden = UUID.randomUUID();
        jdbc.update("INSERT INTO gifts (id, name, description, image_url, points_cost, min_tier, "
                + "stock, active) VALUES (?, 'Đã ẩn', 'x', 'x', 100, 0, 5, FALSE)", hidden);

        as(UUID.randomUUID(), "/loyalty/gifts")
                .andExpect(jsonPath(field(hidden.toString(), "id"), hasSize(0)));
    }

    @Test
    @DisplayName("A tier 0 account cannot have a tier 2 gift")
    void blocksAGiftAboveTheTier() throws Exception {
        as(withPoints("10000000"), "/loyalty/gifts")
                .andExpect(jsonPath(field(GIFT_TIER_2, "eligible")).value(false));
    }

    @Test
    @DisplayName("A gift costing more than the balance is not eligible")
    void blocksAGiftBeyondTheBalance() throws Exception {
        as(withPoints("10000000"), "/loyalty/gifts")
                .andExpect(jsonPath(field(GIFT_UNAFFORDABLE, "eligible")).value(false));
    }

    @Test
    @DisplayName("An affordable, in-stock, tier 0 gift is eligible once there are points")
    void allowsAnAffordableGift() throws Exception {
        as(withPoints("10000000"), "/loyalty/gifts")
                .andExpect(jsonPath(field(GIFT_TIER_0, "eligible")).value(true));
    }

    @Test
    @DisplayName("A gift with no stock says so")
    void reportsStock() throws Exception {
        as(withPoints("10000000"), "/loyalty/gifts")
                .andExpect(jsonPath(field(GIFT_OUT_OF_STOCK, "inStock")).value(false));
    }

    @Test
    @DisplayName("A claimed gift is marked claimed")
    void marksAClaimedGift() throws Exception {
        UUID userId = withPoints("10000000");
        jdbc.update("INSERT INTO gift_redemptions (id, user_id, gift_id, code, gift_name, "
                        + "points_spent, claimed_at) VALUES (?, ?, ?, ?, 'Ốp lưng silicon', 500, NOW())",
                UUID.randomUUID(), userId, UUID.fromString(GIFT_TIER_0), claimCode());

        as(userId, "/loyalty/gifts")
                .andExpect(jsonPath(field(GIFT_TIER_0, "alreadyClaimed")).value(true));
    }

    @Test
    @DisplayName("A claimed gift is no longer eligible, whatever the balance")
    void blocksAReClaim() throws Exception {
        UUID userId = withPoints("10000000");
        jdbc.update("INSERT INTO gift_redemptions (id, user_id, gift_id, code, gift_name, "
                        + "points_spent, claimed_at) VALUES (?, ?, ?, ?, 'Ốp lưng silicon', 500, NOW())",
                UUID.randomUUID(), userId, UUID.fromString(GIFT_TIER_0), claimCode());

        as(userId, "/loyalty/gifts")
                .andExpect(jsonPath(field(GIFT_TIER_0, "eligible")).value(false));
    }

    @Test
    @DisplayName("A request with no X-User-Id header is rejected before the handler runs")
    void requiresTheGatewayHeader() throws Exception {
        mvc.perform(get("/loyalty/me")).andExpect(status().isUnauthorized());
    }
}
