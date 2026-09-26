package vn.techies.ecommerce.gateway.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import vn.techies.ecommerce.common.error.ApiError;
import vn.techies.ecommerce.common.error.ErrorCode;
import vn.techies.ecommerce.common.logging.RequestContext;
import vn.techies.ecommerce.common.security.UserPrincipal;
import vn.techies.ecommerce.gateway.config.GatewayProperties;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Validates the JWT once, at the edge, and tells downstream services who the caller is.
 *
 * <p>Services trust the X-User-Id header this filter sets. That trust is only sound because
 * inbound X-User-* headers are stripped here first (see {@link #stripForgedIdentityHeaders})
 * and because no service port is published to the host. See docs/SPEC-gateway.md.
 */
@Component
public class JwtAuthenticationFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);
    private static final String BEARER = "Bearer ";

    private final AntPathMatcher pathMatcher = new AntPathMatcher();
    private final ObjectMapper objectMapper;
    private final SecretKey key;
    private final List<String> publicPaths;

    public JwtAuthenticationFilter(GatewayProperties properties, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.publicPaths = properties.publicPaths() == null ? List.of() : properties.publicPaths();

        byte[] secret = properties.jwt().secret().getBytes(StandardCharsets.UTF_8);
        if (secret.length < 32) {
            throw new IllegalStateException(
                    "techies.jwt.secret must be at least 32 bytes for HS256, got " + secret.length);
        }
        this.key = Keys.hmacShaKeyFor(secret);
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();
        String method = exchange.getRequest().getMethod().name();

        // Strip first, unconditionally. A public route must not be able to smuggle an
        // identity downstream either.
        ServerHttpRequest sanitized = stripForgedIdentityHeaders(exchange.getRequest());

        if (isPublic(method, path)) {
            return chain.filter(exchange.mutate().request(sanitized).build());
        }

        String authorization = sanitized.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authorization == null || !authorization.startsWith(BEARER)) {
            return reject(exchange, ErrorCode.UNAUTHENTICATED, "Authentication required",
                    authorization == null ? "no Authorization header" : "not a Bearer token");
        }

        try {
            Claims claims = Jwts.parser().verifyWith(key).build()
                    .parseSignedClaims(authorization.substring(BEARER.length()).trim())
                    .getPayload();

            ServerHttpRequest authenticated = sanitized.mutate()
                    .header(UserPrincipal.HEADER_USER_ID, claims.getSubject())
                    .header(UserPrincipal.HEADER_USER_EMAIL, claims.get("email", String.class))
                    .build();

            return chain.filter(exchange.mutate().request(authenticated).build());

        } catch (JwtException | IllegalArgumentException ex) {
            return reject(exchange, ErrorCode.INVALID_TOKEN, "Token is invalid or has expired",
                    ex.getClass().getSimpleName() + ": " + ex.getMessage());
        }
    }

    /**
     * Removes any client-supplied identity headers.
     *
     * <p>This is the single most important line in the module: without it, anyone could set
     * X-User-Id themselves and impersonate any account, because downstream services take that
     * header at face value.
     */
    private static ServerHttpRequest stripForgedIdentityHeaders(ServerHttpRequest request) {
        return request.mutate()
                .headers(headers -> {
                    headers.remove(UserPrincipal.HEADER_USER_ID);
                    headers.remove(UserPrincipal.HEADER_USER_EMAIL);
                })
                .build();
    }

    /**
     * A public-path entry is either a bare pattern, or {@code METHOD:pattern} to open only
     * one verb. The method form matters where a path is public to read but not to write:
     * {@code GET:/api/users/*&#47;avatar} exposes profile images to image loaders without also
     * exposing {@code POST /api/users/me/avatar}, which must stay authenticated.
     */
    private boolean isPublic(String method, String path) {
        for (String entry : publicPaths) {
            int colon = entry.indexOf(':');
            if (colon > 0 && entry.substring(0, colon).chars().allMatch(Character::isUpperCase)) {
                if (entry.substring(0, colon).equals(method)
                        && pathMatcher.match(entry.substring(colon + 1), path)) {
                    return true;
                }
            } else if (pathMatcher.match(entry, path)) {
                return true;
            }
        }
        return false;
    }

    private Mono<Void> reject(ServerWebExchange exchange, ErrorCode code, String message,
                              String reason) {
        String requestId = exchange.getRequest().getHeaders()
                .getFirst(RequestContext.HEADER_REQUEST_ID);
        org.slf4j.MDC.put(RequestContext.MDC_REQUEST_ID, requestId == null ? "unknown" : requestId);
        try {
            log.warn("AUTH REJECTED {} {} -> {} {} | {}",
                    exchange.getRequest().getMethod(), exchange.getRequest().getURI().getRawPath(),
                    code.status(), code, reason);
        } finally {
            org.slf4j.MDC.remove(RequestContext.MDC_REQUEST_ID);
        }

        exchange.getResponse().setStatusCode(
                org.springframework.http.HttpStatus.valueOf(code.status()));
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);

        ApiError error = ApiError.of(code, message, exchange.getRequest().getURI().getPath());
        try {
            DataBuffer buffer = exchange.getResponse().bufferFactory()
                    .wrap(objectMapper.writeValueAsBytes(error));
            return exchange.getResponse().writeWith(Mono.just(buffer));
        } catch (Exception ex) {
            return exchange.getResponse().setComplete();
        }
    }

    @Override
    public int getOrder() {
        // Ahead of the routing filter, so an unauthenticated request never reaches a service.
        return -100;
    }
}
