package vn.techies.ecommerce.loyalty.api;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.techies.ecommerce.loyalty.api.dto.LoyaltyDtos.AwardRequest;
import vn.techies.ecommerce.loyalty.api.dto.LoyaltyDtos.AwardResponse;
import vn.techies.ecommerce.loyalty.service.LoyaltyService;

@RestController
@RequestMapping("/loyalty")
@RequiredArgsConstructor
class PointsController {

    private final LoyaltyService loyalty;

    /**
     * Internal only: the gateway does not route this, so a customer cannot award themselves
     * points by calling it. order-service is the sole caller.
     */
    @PostMapping("/points")
    AwardResponse award(@Valid @RequestBody AwardRequest request) {
        return loyalty.award(request);
    }
}
