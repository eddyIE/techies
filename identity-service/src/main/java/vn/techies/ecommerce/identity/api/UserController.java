package vn.techies.ecommerce.identity.api;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import vn.techies.ecommerce.common.security.CurrentUser;
import vn.techies.ecommerce.common.security.UserPrincipal;
import vn.techies.ecommerce.identity.api.dto.AuthDtos.UserResponse;
import vn.techies.ecommerce.identity.api.dto.UserDtos.ChangePasswordRequest;
import vn.techies.ecommerce.identity.api.dto.UserDtos.UpdateProfileRequest;
import vn.techies.ecommerce.identity.service.UserService;

import java.util.UUID;

@RestController
@RequestMapping("/users")
@RequiredArgsConstructor
class UserController {

    private final UserService userService;

    @GetMapping("/me")
    UserResponse me(@CurrentUser UserPrincipal principal) {
        return userService.get(principal.userId());
    }

    @GetMapping("/{id}")
    UserResponse byId(@PathVariable UUID id, @CurrentUser UserPrincipal principal) {
        return userService.getScoped(id, principal.userId());
    }

    @PutMapping("/me")
    UserResponse updateMe(@CurrentUser UserPrincipal principal,
                          @Valid @RequestBody UpdateProfileRequest request) {
        return userService.updateProfile(principal.userId(), request);
    }

    @PutMapping("/me/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void changePassword(@CurrentUser UserPrincipal principal,
                        @Valid @RequestBody ChangePasswordRequest request) {
        userService.changePassword(principal.userId(), request);
    }
}
