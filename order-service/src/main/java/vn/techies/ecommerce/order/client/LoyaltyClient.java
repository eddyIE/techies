package vn.techies.ecommerce.order.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PathVariable;
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

    /**
     * Claims the voucher for this order, before payment, so two carts cannot both spend it.
     * Idempotent on {@code (code, orderRef)}.
     */
    @PostMapping("/vouchers/{code}/consume")
    ConsumeResponse consume(@PathVariable("code") String code, @RequestBody ConsumeRequest request);

    /** Compensation. 409 for a code this order never consumed, like NOTHING_TO_RESTORE. */
    @PostMapping("/vouchers/{code}/release")
    ReleaseResponse release(@PathVariable("code") String code, @RequestBody ReleaseRequest request);

    /** {@code amountSpent} is {@code subtotal - discount}: the goods, shipping excluded. */
    record AwardRequest(String orderRef, UUID userId, BigDecimal amountSpent) {
    }

    record AwardResponse(boolean awarded, int points, int tier, List<VoucherSummary> vouchersIssued) {
    }

    record VoucherSummary(String code, int tier, int discountPercent) {
    }

    record ConsumeRequest(UUID userId, String orderRef, BigDecimal subtotal) {
    }

    record ConsumeResponse(String code, BigDecimal discount) {
    }

    record ReleaseRequest(String orderRef) {
    }

    record ReleaseResponse(String code, boolean released) {
    }
}
