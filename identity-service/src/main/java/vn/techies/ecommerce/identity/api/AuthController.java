package vn.techies.ecommerce.identity.api;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import vn.techies.ecommerce.identity.api.dto.AuthDtos.CheckEmailRequest;
import vn.techies.ecommerce.identity.api.dto.AuthDtos.CheckEmailResponse;
import vn.techies.ecommerce.identity.api.dto.AuthDtos.LoginRequest;
import vn.techies.ecommerce.identity.api.dto.AuthDtos.LoginResponse;
import vn.techies.ecommerce.identity.api.dto.AuthDtos.RegisterRequest;
import vn.techies.ecommerce.identity.api.dto.AuthDtos.RegisterResponse;
import vn.techies.ecommerce.identity.api.dto.AuthDtos.ResendCodeRequest;
import vn.techies.ecommerce.identity.api.dto.AuthDtos.ResetPasswordRequest;
import vn.techies.ecommerce.identity.api.dto.AuthDtos.VerifyEmailRequest;
import vn.techies.ecommerce.identity.service.AuthService;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
class AuthController {

    private final AuthService authService;

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    RegisterResponse register(@Valid @RequestBody RegisterRequest request) {
        return authService.register(request);
    }

    @PostMapping("/login")
    LoginResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request);
    }

    /** Finishes a registration. Returns a token, so the client does not log in a second time. */
    @PostMapping("/verify-email")
    LoginResponse verifyEmail(@Valid @RequestBody VerifyEmailRequest request) {
        return authService.verifyEmail(request);
    }

    /**
     * Mails a code, for a pending registration or to start a password reset. Always 204, even
     * for an unknown address: see {@code AuthService#resendCode}.
     */
    @PostMapping("/resend-otp")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void resendOtp(@Valid @RequestBody ResendCodeRequest request) {
        authService.resendCode(request);
    }

    @PostMapping("/check-email")
    CheckEmailResponse checkEmail(@Valid @RequestBody CheckEmailRequest request) {
        return authService.checkEmail(request.email());
    }

    @PostMapping("/reset-password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        authService.resetPassword(request);
    }
}
