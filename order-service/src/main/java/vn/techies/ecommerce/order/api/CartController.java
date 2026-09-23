package vn.techies.ecommerce.order.api;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import vn.techies.ecommerce.common.security.CurrentUser;
import vn.techies.ecommerce.common.security.UserPrincipal;
import vn.techies.ecommerce.order.api.dto.CartDtos.AddCartItemRequest;
import vn.techies.ecommerce.order.api.dto.CartDtos.CartResponse;
import vn.techies.ecommerce.order.api.dto.CartDtos.UpdateCartItemRequest;
import vn.techies.ecommerce.order.service.CartService;

import java.util.UUID;

@RestController
@RequestMapping("/cart")
@RequiredArgsConstructor
class CartController {

    private final CartService cartService;

    @GetMapping
    CartResponse view(@CurrentUser UserPrincipal principal) {
        return cartService.view(principal.userId());
    }

    @PostMapping("/items")
    @ResponseStatus(HttpStatus.CREATED)
    CartResponse add(@CurrentUser UserPrincipal principal,
                     @Valid @RequestBody AddCartItemRequest request) {
        return cartService.add(principal.userId(), request);
    }

    @PutMapping("/items/{itemId}")
    CartResponse update(@CurrentUser UserPrincipal principal, @PathVariable UUID itemId,
                        @Valid @RequestBody UpdateCartItemRequest request) {
        return cartService.updateQuantity(principal.userId(), itemId, request.quantity());
    }

    @DeleteMapping("/items/{itemId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void remove(@CurrentUser UserPrincipal principal, @PathVariable UUID itemId) {
        cartService.remove(principal.userId(), itemId);
    }
}
