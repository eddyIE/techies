package vn.techies.ecommerce.identity.service;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;
import vn.techies.ecommerce.identity.config.JwtProperties;
import vn.techies.ecommerce.identity.domain.User;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

@Service
public class JwtService {

    private final SecretKey key;
    private final Duration ttl;

    public JwtService(JwtProperties properties) {
        byte[] secret = properties.secret().getBytes(StandardCharsets.UTF_8);
        if (secret.length < 32) {
            // Fail at startup rather than issuing tokens the gateway cannot verify.
            throw new IllegalStateException(
                    "techies.jwt.secret must be at least 32 bytes for HS256, got " + secret.length);
        }
        this.key = Keys.hmacShaKeyFor(secret);
        this.ttl = Duration.ofDays(properties.ttlDays());
    }

    public String issue(User user) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(user.getId().toString())
                .claim("email", user.getEmail())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(ttl)))
                .signWith(key)
                .compact();
    }

    public long ttlSeconds() {
        return ttl.toSeconds();
    }
}
