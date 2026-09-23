package vn.techies.ecommerce.identity.api;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.techies.ecommerce.identity.api.dto.AddressDtos.AddressResponse;
import vn.techies.ecommerce.identity.service.AddressService;

import java.util.UUID;

/**
 * Service-to-service only. The gateway does not route /internal/**, so this is reachable
 * only from inside the compose network — order-service calls it to snapshot a shipping
 * address at checkout.
 */
@RestController
@RequestMapping("/internal/addresses")
@RequiredArgsConstructor
class InternalAddressController {

    private final AddressService addressService;

    @GetMapping("/{id}")
    AddressResponse get(@PathVariable UUID id, @RequestParam UUID userId) {
        // Ownership is still enforced: a wrong userId yields 404, never another user's address.
        return addressService.getOwned(id, userId);
    }
}
