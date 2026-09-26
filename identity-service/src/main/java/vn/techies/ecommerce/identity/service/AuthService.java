package vn.techies.ecommerce.identity.service;

import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.techies.ecommerce.common.error.ApiException;
import vn.techies.ecommerce.common.error.ErrorCode;
import vn.techies.ecommerce.identity.api.dto.AuthDtos.CheckEmailResponse;
import vn.techies.ecommerce.identity.api.dto.AuthDtos.LoginRequest;
import vn.techies.ecommerce.identity.api.dto.AuthDtos.LoginResponse;
import vn.techies.ecommerce.identity.api.dto.AuthDtos.RegisterRequest;
import vn.techies.ecommerce.identity.api.dto.AuthDtos.RegisterResponse;
import vn.techies.ecommerce.identity.api.dto.AuthDtos.ResetPasswordRequest;
import vn.techies.ecommerce.identity.api.dto.AuthDtos.UserResponse;
import vn.techies.ecommerce.identity.domain.User;
import vn.techies.ecommerce.identity.repository.UserAvatarRepository;
import vn.techies.ecommerce.identity.repository.UserRepository;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository users;
    private final UserAvatarRepository avatars;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    @Transactional
    public RegisterResponse register(RegisterRequest request) {
        PasswordPolicy.validate(request.password());
        if (users.existsByEmailIgnoreCase(request.email())) {
            throw new ApiException(ErrorCode.EMAIL_ALREADY_EXISTS,
                    "An account with this email already exists");
        }
        User user = users.save(User.create(
                request.email(),
                passwordEncoder.encode(request.password()),
                request.fullName(),
                request.phone()));
        return new RegisterResponse(user.getId(), user.getEmail());
    }

    @Transactional(readOnly = true)
    public LoginResponse login(LoginRequest request) {
        // Identical failure for unknown email and wrong password: the response must not
        // reveal whether an account exists.
        User user = users.findByEmailIgnoreCase(request.email())
                .filter(u -> passwordEncoder.matches(request.password(), u.getPasswordHash()))
                .orElseThrow(() -> new ApiException(ErrorCode.INVALID_CREDENTIALS,
                        "Email or password is incorrect"));

        return new LoginResponse(jwtService.issue(user), "Bearer", jwtService.ttlSeconds(),
                toResponse(user, avatars.existsByUserId(user.getId())));
    }

    @Transactional(readOnly = true)
    public CheckEmailResponse checkEmail(String email) {
        return new CheckEmailResponse(email.toLowerCase(), users.existsByEmailIgnoreCase(email));
    }

    /**
     * Resets a password given only the email. No token, no out-of-band verification — see the
     * limitation recorded in docs/SPEC-identity.md and docs/EXTENSIONS.md.
     */
    @Transactional
    public void resetPassword(ResetPasswordRequest request) {
        PasswordPolicy.validate(request.newPassword());
        User user = users.findByEmailIgnoreCase(request.email())
                .orElseThrow(() -> new ApiException(ErrorCode.ACCOUNT_NOT_FOUND,
                        "No account exists for this email"));
        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        user.touch();
    }

    public static UserResponse toResponse(User user, boolean hasAvatar) {
        return new UserResponse(user.getId(), user.getEmail(), user.getFullName(), user.getPhone(),
                hasAvatar ? "/users/" + user.getId() + "/avatar" : null);
    }
}
