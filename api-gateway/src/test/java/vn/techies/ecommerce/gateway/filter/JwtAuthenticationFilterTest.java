package vn.techies.ecommerce.gateway.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import vn.techies.ecommerce.common.security.UserPrincipal;
import vn.techies.ecommerce.gateway.config.GatewayProperties;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class JwtAuthenticationFilterTest {

    private static final String SECRET = "test-secret-that-is-definitely-long-enough-32";
    private static final SecretKey KEY =
            Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));

    private JwtAuthenticationFilter filter;
    private AtomicReference<ServerWebExchange> forwarded;
    private GatewayFilterChain chain;

    @BeforeEach
    void setUp() {
        filter = new JwtAuthenticationFilter(
                new GatewayProperties(new GatewayProperties.Jwt(SECRET),
                        List.of("/api/auth/**", "/api/products/**", "/api/stock/**")),
                new ObjectMapper());

        forwarded = new AtomicReference<>();
        chain = exchange -> {
            forwarded.set(exchange);
            return Mono.empty();
        };
    }

    private String tokenFor(UUID userId, String email, long ttlMillis) {
        Date now = new Date();
        return Jwts.builder()
                .subject(userId.toString())
                .claim("email", email)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + ttlMillis))
                .signWith(KEY)
                .compact();
    }

    private MockServerWebExchange exchange(MockServerHttpRequest request) {
        return MockServerWebExchange.from(request);
    }

    @Test
    @DisplayName("a public route passes through without a token")
    void publicRoutePasses() {
        MockServerWebExchange exchange = exchange(MockServerHttpRequest.get("/api/products").build());

        filter.filter(exchange, chain).block();

        assertThat(forwarded.get()).as("request was forwarded").isNotNull();
        assertThat(exchange.getResponse().getStatusCode()).isNull();
    }

    @Test
    @DisplayName("a protected route without a token is rejected with the shared envelope")
    void missingTokenRejected() {
        MockServerWebExchange exchange = exchange(MockServerHttpRequest.get("/api/cart").build());

        filter.filter(exchange, chain).block();

        assertThat(forwarded.get()).as("never forwarded").isNull();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("a malformed token is rejected")
    void malformedTokenRejected() {
        MockServerWebExchange exchange = exchange(MockServerHttpRequest.get("/api/cart")
                .header(HttpHeaders.AUTHORIZATION, "Bearer not-a-real-token").build());

        filter.filter(exchange, chain).block();

        assertThat(forwarded.get()).isNull();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("an expired token is rejected")
    void expiredTokenRejected() {
        String expired = tokenFor(UUID.randomUUID(), "a@b.com", -1000);
        MockServerWebExchange exchange = exchange(MockServerHttpRequest.get("/api/cart")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + expired).build());

        filter.filter(exchange, chain).block();

        assertThat(forwarded.get()).isNull();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("a token signed with a different key is rejected")
    void wrongSignatureRejected() {
        SecretKey otherKey = Keys.hmacShaKeyFor(
                "a-completely-different-secret-key-of-32b".getBytes(StandardCharsets.UTF_8));
        String foreign = Jwts.builder().subject(UUID.randomUUID().toString())
                .expiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(otherKey).compact();

        MockServerWebExchange exchange = exchange(MockServerHttpRequest.get("/api/cart")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + foreign).build());

        filter.filter(exchange, chain).block();

        assertThat(forwarded.get()).isNull();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("a valid token injects the caller's identity for downstream services")
    void validTokenInjectsIdentity() {
        UUID userId = UUID.randomUUID();
        MockServerWebExchange exchange = exchange(MockServerHttpRequest.get("/api/cart")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(userId, "a@b.com", 60_000))
                .build());

        filter.filter(exchange, chain).block();

        HttpHeaders headers = forwarded.get().getRequest().getHeaders();
        assertThat(headers.getFirst(UserPrincipal.HEADER_USER_ID)).isEqualTo(userId.toString());
        assertThat(headers.getFirst(UserPrincipal.HEADER_USER_EMAIL)).isEqualTo("a@b.com");
    }

    @Test
    @DisplayName("FORGERY DEFENCE: a client-supplied X-User-Id without a token never reaches a service")
    void forgedIdentityWithoutTokenIsRejected() {
        UUID victim = UUID.randomUUID();
        MockServerWebExchange exchange = exchange(MockServerHttpRequest.get("/api/cart")
                .header(UserPrincipal.HEADER_USER_ID, victim.toString())
                .header(UserPrincipal.HEADER_USER_EMAIL, "victim@example.com")
                .build());

        filter.filter(exchange, chain).block();

        assertThat(forwarded.get()).as("impersonation attempt never forwarded").isNull();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("FORGERY DEFENCE: a forged header alongside a valid token is overwritten, not honoured")
    void forgedIdentityWithValidTokenIsOverwritten() {
        UUID attacker = UUID.randomUUID();
        UUID victim = UUID.randomUUID();

        MockServerWebExchange exchange = exchange(MockServerHttpRequest.get("/api/cart")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(attacker, "attacker@x.com", 60_000))
                .header(UserPrincipal.HEADER_USER_ID, victim.toString())
                .header(UserPrincipal.HEADER_USER_EMAIL, "victim@example.com")
                .build());

        filter.filter(exchange, chain).block();

        HttpHeaders headers = forwarded.get().getRequest().getHeaders();
        assertThat(headers.get(UserPrincipal.HEADER_USER_ID)).containsExactly(attacker.toString());
        assertThat(headers.get(UserPrincipal.HEADER_USER_EMAIL)).containsExactly("attacker@x.com");
        assertThat(headers.getFirst(UserPrincipal.HEADER_USER_ID))
                .as("the token wins, never the header").isNotEqualTo(victim.toString());
    }

    @Test
    @DisplayName("FORGERY DEFENCE: a forged header is stripped even on a public route")
    void forgedIdentityStrippedOnPublicRoute() {
        MockServerWebExchange exchange = exchange(MockServerHttpRequest.get("/api/products")
                .header(UserPrincipal.HEADER_USER_ID, UUID.randomUUID().toString())
                .build());

        filter.filter(exchange, chain).block();

        assertThat(forwarded.get().getRequest().getHeaders().get(UserPrincipal.HEADER_USER_ID))
                .as("public routes cannot smuggle an identity either").isNull();
    }

    @Test
    @DisplayName("an Authorization header without the Bearer scheme is rejected")
    void nonBearerSchemeRejected() {
        MockServerWebExchange exchange = exchange(MockServerHttpRequest.get("/api/cart")
                .header(HttpHeaders.AUTHORIZATION, "Basic dXNlcjpwYXNz").build());

        filter.filter(exchange, chain).block();

        assertThat(forwarded.get()).isNull();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("a secret shorter than 32 bytes fails at startup rather than issuing weak tokens")
    void shortSecretFailsFast() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        new JwtAuthenticationFilter(
                                new GatewayProperties(new GatewayProperties.Jwt("too-short"), List.of()),
                                new ObjectMapper()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least 32 bytes");
    }
}
