package vn.techies.ecommerce.order.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.UUID;

@FeignClient(name = "identity-service", path = "/internal/addresses")
public interface IdentityClient {

    /** Ownership is enforced on the identity side: a mismatched userId yields 404. */
    @GetMapping("/{id}")
    AddressSnapshot getAddress(@PathVariable("id") UUID id, @RequestParam("userId") UUID userId);

    record AddressSnapshot(UUID id, String recipientName, String phone, String line1,
                           String ward, String district, String province, boolean isDefault) {
    }
}
