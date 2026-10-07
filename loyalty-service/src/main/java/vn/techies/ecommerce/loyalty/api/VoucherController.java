package vn.techies.ecommerce.loyalty.api;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.techies.ecommerce.loyalty.api.dto.LoyaltyDtos.ConsumeRequest;
import vn.techies.ecommerce.loyalty.api.dto.LoyaltyDtos.ConsumeResponse;
import vn.techies.ecommerce.loyalty.api.dto.LoyaltyDtos.ReleaseRequest;
import vn.techies.ecommerce.loyalty.api.dto.LoyaltyDtos.ReleaseResponse;
import vn.techies.ecommerce.loyalty.service.LoyaltyService;

/**
 * Internal only, like {@code /loyalty/points}: the gateway routes neither, because a customer
 * calling release on their own voucher mid-checkout would get the discount twice.
 */
@RestController
@RequestMapping("/loyalty/vouchers")
@RequiredArgsConstructor
class VoucherController {

    private final LoyaltyService loyalty;

    @PostMapping("/{code}/consume")
    ConsumeResponse consume(@PathVariable String code, @Valid @RequestBody ConsumeRequest request) {
        return loyalty.consume(code, request);
    }

    @PostMapping("/{code}/release")
    ReleaseResponse release(@PathVariable String code, @Valid @RequestBody ReleaseRequest request) {
        return loyalty.release(code, request);
    }
}
