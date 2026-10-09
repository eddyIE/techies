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
import vn.techies.ecommerce.identity.api.dto.AuthDtos.ResendCodeRequest;
import vn.techies.ecommerce.identity.api.dto.AuthDtos.ResetPasswordRequest;
import vn.techies.ecommerce.identity.api.dto.AuthDtos.UserResponse;
import vn.techies.ecommerce.identity.api.dto.AuthDtos.VerifyEmailRequest;
import vn.techies.ecommerce.identity.domain.User;
import vn.techies.ecommerce.identity.domain.VerificationCode.Purpose;
import vn.techies.ecommerce.identity.repository.UserAvatarRepository;
import vn.techies.ecommerce.identity.repository.UserRepository;

import java.util.Optional;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository users;
    private final UserAvatarRepository avatars;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final VerificationService verification;

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
        // No cooldown on this one: nobody asked for it twice, and refusing the very first code
        // of an account would leave it unreachable for a minute with no way to tell why.
        verification.issueAndSend(user, Purpose.REGISTRATION, false);
        return new RegisterResponse(user.getId(), user.getEmail(), true);
    }

    /**
     * Finishes a registration and logs the user straight in. Returning a token here saves the
     * client a round trip it would otherwise make with credentials it already sent once.
     */
    @Transactional
    public LoginResponse verifyEmail(VerifyEmailRequest request) {
        User user = users.findByEmailIgnoreCase(request.email())
                .orElseThrow(() -> new ApiException(ErrorCode.INVALID_VERIFICATION_CODE,
                        "Mã xác thực không đúng hoặc đã được sử dụng"));
        if (user.isEmailVerified()) {
            throw new ApiException(ErrorCode.EMAIL_ALREADY_VERIFIED,
                    "Tài khoản này đã được xác thực");
        }
        verification.consume(user, Purpose.REGISTRATION, request.code());
        user.setEmailVerified(true);
        user.touch();

        return new LoginResponse(jwtService.issue(user), "Bearer", jwtService.ttlSeconds(),
                toResponse(user, avatars.existsByUserId(user.getId())));
    }

    /**
     * Sends a fresh code, for a pending registration or for a password reset.
     *
     * <p>Always 204, even for an address with no account and even for an account that is
     * already verified: the response must not become a second way to enumerate accounts, and
     * the client's screen is the same either way. A genuine user who mistypes their address
     * simply never receives a code, which is the correct outcome.
     */
    @Transactional
    public void resendCode(ResendCodeRequest request) {
        Optional<User> found = users.findByEmailIgnoreCase(request.email());
        if (found.isEmpty()) {
            return;
        }
        User user = found.get();
        boolean pointless = request.purpose() == Purpose.REGISTRATION
                ? user.isEmailVerified()
                : !user.isEmailVerified();
        if (pointless) {
            return;
        }
        verification.issueAndSend(user, request.purpose(), true);
    }

    @Transactional(readOnly = true)
    public LoginResponse login(LoginRequest request) {
        // Identical failure for unknown email and wrong password: the response must not
        // reveal whether an account exists.
        User user = users.findByEmailIgnoreCase(request.email())
                .filter(u -> passwordEncoder.matches(request.password(), u.getPasswordHash()))
                .orElseThrow(() -> new ApiException(ErrorCode.INVALID_CREDENTIALS,
                        "Email or password is incorrect"));

        // Checked only after the password matches. Doing it first would turn login into an
        // account-existence oracle for anyone who can read a status code.
        if (!user.isEmailVerified()) {
            throw new ApiException(ErrorCode.EMAIL_NOT_VERIFIED,
                    "Tài khoản chưa được xác thực. Vui lòng nhập mã đã gửi tới email của bạn");
        }

        return new LoginResponse(jwtService.issue(user), "Bearer", jwtService.ttlSeconds(),
                toResponse(user, avatars.existsByUserId(user.getId())));
    }

    @Transactional(readOnly = true)
    public CheckEmailResponse checkEmail(String email) {
        return new CheckEmailResponse(email.toLowerCase(), users.existsByEmailIgnoreCase(email));
    }

    /**
     * Resets a password against a code mailed to the address, which is the proof of ownership
     * that this flow lacked until identity V3.
     *
     * <p>The password policy is checked first, so a user who picks a weak new password is told
     * that without spending their one-use code on the attempt.
     */
    @Transactional
    public void resetPassword(ResetPasswordRequest request) {
        PasswordPolicy.validate(request.newPassword());
        User user = users.findByEmailIgnoreCase(request.email())
                .orElseThrow(() -> new ApiException(ErrorCode.ACCOUNT_NOT_FOUND,
                        "No account exists for this email"));
        verification.consume(user, Purpose.PASSWORD_RESET, request.code());
        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        user.touch();
    }

    public static UserResponse toResponse(User user, boolean hasAvatar) {
        return new UserResponse(user.getId(), user.getEmail(), user.getFullName(), user.getPhone(),
                hasAvatar ? "/users/" + user.getId() + "/avatar" : null);
    }
}
