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
import vn.techies.ecommerce.identity.api.dto.AuthDtos.ResetPasswordRequest;
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
