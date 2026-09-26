package vn.techies.ecommerce.identity.service;

import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.techies.ecommerce.common.error.ApiException;
import vn.techies.ecommerce.common.error.ErrorCode;
import vn.techies.ecommerce.identity.api.dto.AuthDtos.UserResponse;
import vn.techies.ecommerce.identity.api.dto.UserDtos.ChangePasswordRequest;
import vn.techies.ecommerce.identity.api.dto.UserDtos.UpdateProfileRequest;
import vn.techies.ecommerce.identity.domain.User;
import vn.techies.ecommerce.identity.repository.UserAvatarRepository;
import vn.techies.ecommerce.identity.repository.UserRepository;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository users;
    private final UserAvatarRepository avatars;
    private final PasswordEncoder passwordEncoder;

    @Transactional(readOnly = true)
    public UserResponse get(UUID userId) {
        return AuthService.toResponse(load(userId), avatars.existsByUserId(userId));
    }

    /**
     * Sheet row 6 asked for GET /user/:id. Only the caller's own id is permitted; anything
     * else is a 403 rather than a 404, because the resource does exist — the caller just
     * has no business reading it.
     */
    @Transactional(readOnly = true)
    public UserResponse getScoped(UUID requestedId, UUID callerId) {
        if (!requestedId.equals(callerId)) {
            throw ApiException.forbidden("you may only read your own profile");
        }
        return get(requestedId);
    }

    @Transactional
    public UserResponse updateProfile(UUID userId, UpdateProfileRequest request) {
        User user = load(userId);
        // Email is deliberately not updatable: it is the login identifier.
        user.setFullName(request.fullName());
        user.setPhone(request.phone());
        user.touch();
        return AuthService.toResponse(user, avatars.existsByUserId(userId));
    }

    @Transactional
    public void changePassword(UUID userId, ChangePasswordRequest request) {
        User user = load(userId);
        if (!passwordEncoder.matches(request.currentPassword(), user.getPasswordHash())) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Current password is incorrect");
        }
        PasswordPolicy.validate(request.newPassword());
        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        user.touch();
        // Existing JWTs stay valid: there is no denylist. Documented in docs/EXTENSIONS.md.
    }

    private User load(UUID userId) {
        return users.findById(userId)
                .orElseThrow(() -> new ApiException(ErrorCode.ACCOUNT_NOT_FOUND, "Account not found"));
    }
}
