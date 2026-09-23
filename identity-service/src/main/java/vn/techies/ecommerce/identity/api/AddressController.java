package vn.techies.ecommerce.identity.api;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import vn.techies.ecommerce.common.security.CurrentUser;
import vn.techies.ecommerce.common.security.UserPrincipal;
import vn.techies.ecommerce.identity.api.dto.AddressDtos.AddressRequest;
import vn.techies.ecommerce.identity.api.dto.AddressDtos.AddressResponse;
import vn.techies.ecommerce.identity.service.AddressService;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/addresses")
@RequiredArgsConstructor
class AddressController {

    private final AddressService addressService;

    @GetMapping
    List<AddressResponse> list(@CurrentUser UserPrincipal principal) {
        return addressService.list(principal.userId());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    AddressResponse create(@CurrentUser UserPrincipal principal,
                           @Valid @RequestBody AddressRequest request) {
        return addressService.create(principal.userId(), request);
    }

    @PutMapping("/{id}")
    AddressResponse update(@CurrentUser UserPrincipal principal, @PathVariable UUID id,
                           @Valid @RequestBody AddressRequest request) {
        return addressService.update(principal.userId(), id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@CurrentUser UserPrincipal principal, @PathVariable UUID id) {
        addressService.delete(principal.userId(), id);
    }
}
