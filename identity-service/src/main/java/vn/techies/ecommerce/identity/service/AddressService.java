package vn.techies.ecommerce.identity.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.techies.ecommerce.common.error.ApiException;
import vn.techies.ecommerce.common.error.ErrorCode;
import vn.techies.ecommerce.identity.api.dto.AddressDtos.AddressRequest;
import vn.techies.ecommerce.identity.api.dto.AddressDtos.AddressResponse;
import vn.techies.ecommerce.identity.domain.Address;
import vn.techies.ecommerce.identity.repository.AddressRepository;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AddressService {

    private final AddressRepository addresses;

    @Transactional(readOnly = true)
    public List<AddressResponse> list(UUID userId) {
        return addresses.findByUserIdOrderByIsDefaultDescCreatedAtDesc(userId).stream()
                .map(AddressService::toResponse)
                .toList();
    }

    @Transactional
    public AddressResponse create(UUID userId, AddressRequest request) {
        boolean first = addresses.findByUserIdOrderByIsDefaultDescCreatedAtDesc(userId).isEmpty();
        // The first address a user adds becomes their default whether they asked or not —
        // otherwise checkout would have nothing to pre-select.
        boolean makeDefault = request.isDefault() || first;
        if (makeDefault) {
            clearExistingDefault(userId);
        }
        Address saved = addresses.save(Address.create(userId, request.recipientName(), request.phone(),
                request.line1(), request.ward(), request.district(), request.province(), makeDefault));
        return toResponse(saved);
    }

    @Transactional
    public AddressResponse update(UUID userId, UUID addressId, AddressRequest request) {
        Address address = load(addressId, userId);
        if (request.isDefault() && !address.isDefault()) {
            clearExistingDefault(userId);
        }
        address.setRecipientName(request.recipientName());
        address.setPhone(request.phone());
        address.setLine1(request.line1());
        address.setWard(request.ward());
        address.setDistrict(request.district());
        address.setProvince(request.province());
        // An address cannot un-default itself: the user promotes another one instead.
        address.setDefault(request.isDefault() || address.isDefault());
        address.touch();
        return toResponse(address);
    }

    @Transactional
    public void delete(UUID userId, UUID addressId) {
        Address address = load(addressId, userId);
        boolean wasDefault = address.isDefault();
        addresses.delete(address);
        addresses.flush();

        if (wasDefault) {
            // Promote the most recently created survivor so the user always has a default.
            addresses.findFirstByUserIdOrderByCreatedAtDesc(userId).ifPresent(next -> {
                next.setDefault(true);
                next.touch();
            });
        }
    }

    @Transactional(readOnly = true)
    public AddressResponse getOwned(UUID addressId, UUID userId) {
        return toResponse(load(addressId, userId));
    }

    private void clearExistingDefault(UUID userId) {
        addresses.clearDefaultFor(userId);
        // Flush so the partial unique index sees the cleared flag before the new default lands.
        addresses.flush();
    }

    private Address load(UUID addressId, UUID userId) {
        return addresses.findByIdAndUserId(addressId, userId)
                .orElseThrow(() -> new ApiException(ErrorCode.ADDRESS_NOT_FOUND, "Address not found"));
    }

    private static AddressResponse toResponse(Address a) {
        return new AddressResponse(a.getId(), a.getRecipientName(), a.getPhone(), a.getLine1(),
                a.getWard(), a.getDistrict(), a.getProvince(), a.isDefault());
    }
}
