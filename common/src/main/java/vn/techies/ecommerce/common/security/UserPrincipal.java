package vn.techies.ecommerce.common.security;

import java.util.UUID;

/**
 * The authenticated caller, reconstructed downstream from the X-User-Id / X-User-Email headers
 * that the gateway injects. Services never parse the JWT themselves.
 */
public record UserPrincipal(UUID userId, String email) {

    public static final String HEADER_USER_ID = "X-User-Id";
    public static final String HEADER_USER_EMAIL = "X-User-Email";
}
