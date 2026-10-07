package vn.techies.ecommerce.loyalty.api;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.techies.ecommerce.common.security.CurrentUser;
import vn.techies.ecommerce.common.security.UserPrincipal;
import vn.techies.ecommerce.loyalty.api.dto.LoyaltyDtos.GiftResponse;
import vn.techies.ecommerce.loyalty.api.dto.LoyaltyDtos.MeResponse;
import vn.techies.ecommerce.loyalty.api.dto.LoyaltyDtos.VoucherSummary;
import vn.techies.ecommerce.loyalty.service.LoyaltyService;

import java.util.List;

/**
 * The customer-facing half of the module, routed by the gateway under /api/loyalty/**. Every
 * response states the tier as a number: naming it is the app's decision, so "Đồng / Bạc / Vàng"
 * can change without a server release.
 */
@RestController
@RequestMapping("/loyalty")
@RequiredArgsConstructor
class LoyaltyController {

    private final LoyaltyService loyalty;

    @GetMapping("/me")
    MeResponse me(@CurrentUser UserPrincipal user) {
        return loyalty.summaryFor(user.userId());
    }

    @GetMapping("/gifts")
    List<GiftResponse> gifts(@CurrentUser UserPrincipal user) {
        return loyalty.giftsFor(user.userId());
    }

    @GetMapping("/vouchers")
    List<VoucherSummary> vouchers(@CurrentUser UserPrincipal user) {
        return loyalty.vouchersFor(user.userId());
    }
}
