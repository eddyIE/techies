package vn.techies.ecommerce.identity.api.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public final class AuthDtos {

    private AuthDtos() {
    }

    public record RegisterRequest(
            @NotBlank @Email String email,
            @NotBlank String password,
            @NotBlank @Size(max = 120) String fullName,
            @NotBlank @Pattern(regexp = "^[0-9]{9,11}$", message = "must be 9-11 digits") String phone) {
    }

    public record RegisterResponse(UUID userId, String email) {
    }

    public record LoginRequest(
            @NotBlank @Email String email,
            @NotBlank String password) {
    }

    public record LoginResponse(String accessToken, String tokenType, long expiresIn, UserResponse user) {
    }

    public record CheckEmailRequest(@NotBlank @Email String email) {
    }

    public record CheckEmailResponse(String email, boolean exists) {
    }

    /**
     * Deliberately carries no proof of ownership — see docs/SPEC-identity.md. Anyone knowing
     * the email can reset the account. Accepted, documented limitation of this project.
     */
    public record ResetPasswordRequest(
            @NotBlank @Email String email,
            @NotBlank String newPassword) {
    }

    public record UserResponse(UUID id, String email, String fullName, String phone) {
    }
}
