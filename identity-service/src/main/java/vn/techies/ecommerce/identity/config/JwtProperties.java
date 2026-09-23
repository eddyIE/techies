package vn.techies.ecommerce.identity.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param secret HS256 signing key, shared with api-gateway. Must be >= 32 bytes.
 * @param ttlDays token lifetime. 30 days by decision: no refresh endpoint exists, so the user
 *                re-logs in manually when it expires (docs/SPEC-identity.md).
 */
@ConfigurationProperties(prefix = "techies.jwt")
public record JwtProperties(String secret, long ttlDays) {
}
