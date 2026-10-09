package vn.techies.ecommerce.identity.api.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import vn.techies.ecommerce.identity.domain.VerificationCode.Purpose;

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

    /**
     * @param verificationRequired always true. Present so the client can branch on the response
     *                             rather than on a hardcoded assumption about this flow, and so
     *                             turning verification off later is not a breaking change.
     */
    public record RegisterResponse(UUID userId, String email, boolean verificationRequired) {
    }

    public record VerifyEmailRequest(
            @NotBlank @Email String email,
            @NotBlank @Pattern(regexp = "^[0-9]{6}$", message = "must be 6 digits") String code) {
    }

    public record ResendCodeRequest(
            @NotBlank @Email String email,
            @NotNull Purpose purpose) {
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
     * {@code code} is the proof of ownership: the 6-digit code mailed to that address by
     * {@code /auth/resend-otp} with purpose {@code PASSWORD_RESET}. Until it existed, this
     * request needed nothing but the email — that was the hole it closes.
     */
    public record ResetPasswordRequest(
            @NotBlank @Email String email,
            @NotBlank @Pattern(regexp = "^[0-9]{6}$", message = "must be 6 digits") String code,
            @NotBlank String newPassword) {
    }

    /**
     * @param avatarUrl path to the profile image, relative to the API base URL, or null when
     *                  the user has not uploaded one. The client joins it to its own base:
     *                  {@code BuildConfig.API_BASE_URL + user.avatarUrl}.
     */
    public record UserResponse(UUID id, String email, String fullName, String phone,
                               String avatarUrl) {
    }
}
