package vn.techies.ecommerce.identity.service;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import vn.techies.ecommerce.common.error.ApiException;
import vn.techies.ecommerce.common.error.ErrorCode;
import vn.techies.ecommerce.identity.domain.UserAvatar;
import vn.techies.ecommerce.identity.repository.UserAvatarRepository;
import vn.techies.ecommerce.identity.repository.UserRepository;

import java.io.IOException;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AvatarService {

    private static final Logger log = LoggerFactory.getLogger(AvatarService.class);

    private final UserAvatarRepository avatars;
    private final UserRepository users;

    /** Uploading again replaces the existing image; there is no history to keep. */
    @Transactional
    public void upload(UUID userId, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "No image was uploaded");
        }
        if (!users.existsById(userId)) {
            throw new ApiException(ErrorCode.ACCOUNT_NOT_FOUND, "Account not found");
        }

        byte[] data;
        try {
            data = file.getBytes();
        } catch (IOException ex) {
            throw new ApiException(ErrorCode.MALFORMED_REQUEST, "Could not read the uploaded file", ex);
        }

        String contentType = ImageValidator.validate(data, file.getContentType(), file.getOriginalFilename());

        avatars.findById(userId).ifPresentOrElse(
                existing -> existing.replaceWith(contentType, data),
                () -> avatars.save(UserAvatar.create(userId, contentType, data)));

        log.info("Avatar updated for user {} ({}, {} KB)", userId, contentType, data.length / 1024);
    }

    /** Public read: rendering a profile picture needs no token. */
    @Transactional(readOnly = true)
    public UserAvatar get(UUID userId) {
        return avatars.findById(userId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "This user has no profile image"));
    }

    @Transactional
    public void delete(UUID userId) {
        if (!avatars.existsByUserId(userId)) {
            throw new ApiException(ErrorCode.NOT_FOUND, "This user has no profile image");
        }
        avatars.deleteById(userId);
        log.info("Avatar removed for user {}", userId);
    }

    @Transactional(readOnly = true)
    public boolean hasAvatar(UUID userId) {
        return avatars.existsByUserId(userId);
    }
}
