package vn.techies.ecommerce.order.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * The only arrow from `order` to `loyalty`, and it points one way: loyalty calls nothing back,
 * which is what keeps the dependency graph acyclic (docs/CAPABILITY-MAP.md).
 */
@FeignClient(name = "loyalty-service", path = "/loyalty")
public interface LoyaltyClient {

    @PostMapping("/points")
    AwardResponse award(@RequestBody AwardRequest request);

    /** {@code amountSpent} is {@code subtotal - discount}: the goods, shipping excluded. */
    record AwardRequest(String orderRef, UUID userId, BigDecimal amountSpent) {
    }

    record AwardResponse(boolean awarded, int points, int tier, List<VoucherSummary> vouchersIssued) {
    }

    record VoucherSummary(String code, int tier, int discountPercent) {
    }
}
