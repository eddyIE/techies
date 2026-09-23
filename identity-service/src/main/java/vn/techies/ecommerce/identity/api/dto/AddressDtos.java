package vn.techies.ecommerce.identity.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public final class AddressDtos {

    private AddressDtos() {
    }

    public record AddressRequest(
            @NotBlank @Size(max = 120) String recipientName,
            @NotBlank @Pattern(regexp = "^[0-9]{9,11}$", message = "must be 9-11 digits") String phone,
            @NotBlank @Size(max = 255) String line1,
            @NotBlank @Size(max = 120) String ward,
            @NotBlank @Size(max = 120) String district,
            @NotBlank @Size(max = 120) String province,
            boolean isDefault) {
    }

    public record AddressResponse(
            UUID id, String recipientName, String phone, String line1,
            String ward, String district, String province, boolean isDefault) {
    }
}
