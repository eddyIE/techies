package vn.techies.ecommerce.identity.api;

import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import vn.techies.ecommerce.common.security.CurrentUser;
import vn.techies.ecommerce.common.security.UserPrincipal;
import vn.techies.ecommerce.identity.domain.UserAvatar;
import vn.techies.ecommerce.identity.service.AvatarService;

import java.time.Duration;
import java.util.UUID;

@RestController
@RequestMapping("/users")
@RequiredArgsConstructor
class AvatarController {

    private final AvatarService avatarService;

    /**
     * Multipart upload under the field name {@code file}. PNG or JPEG, 2MB maximum.
     * Uploading again replaces the previous image.
     */
    @PostMapping(value = "/me/avatar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void upload(@CurrentUser UserPrincipal principal, @RequestParam("file") MultipartFile file) {
        avatarService.upload(principal.userId(), file);
    }

    /**
     * Public: an image library on the device loads this straight into a view, which it
     * cannot easily do when a bearer token is required.
     */
    @GetMapping("/{id}/avatar")
    ResponseEntity<byte[]> get(@PathVariable UUID id) {
        UserAvatar avatar = avatarService.get(id);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(avatar.getContentType()))
                .contentLength(avatar.getSizeBytes())
                // Private, because a profile image belongs to one person and should not be
                // held by a shared cache. The app may still cache it locally.
                .cacheControl(CacheControl.maxAge(Duration.ofMinutes(5)).cachePrivate())
                .body(avatar.getData());
    }

    @DeleteMapping("/me/avatar")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@CurrentUser UserPrincipal principal) {
        avatarService.delete(principal.userId());
    }
}
