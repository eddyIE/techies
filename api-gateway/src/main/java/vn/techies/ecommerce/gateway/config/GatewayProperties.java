package vn.techies.ecommerce.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "techies")
public record GatewayProperties(Jwt jwt, List<String> publicPaths) {

    /** @param secret must match identity-service's signing key, or every token fails to verify. */
    public record Jwt(String secret) {
    }
}
