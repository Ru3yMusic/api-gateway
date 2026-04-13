package com.rubymusic.gateway.filter;

import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.lang.reflect.Field;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.Date;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for JwtAuthFilter — verifies:
 * 1. Original Authorization header is forwarded downstream (zero-trust requirement).
 * 2. Identity headers are correctly set from JWT claims.
 * 3. Missing / invalid / malformed tokens are rejected with 401.
 *
 * <p>All tests run without a Spring context. The RSA key pair is generated in-memory
 * so these tests work in any environment without external configuration.
 */
class JwtAuthFilterTest {

    private JwtAuthFilter filter;
    private KeyPair keyPair;
    private String validToken;

    @BeforeEach
    void setUp() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        keyPair = generator.generateKeyPair();

        // Build filter and inject test public key via reflection (no Spring context needed)
        filter = new JwtAuthFilter();
        String publicKeyB64 = Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());
        Field field = JwtAuthFilter.class.getDeclaredField("publicKeyBase64");
        field.setAccessible(true);
        field.set(filter, publicKeyB64);
        filter.init();

        validToken = buildToken("user-uuid-123", "alice@example.com", "Alice", "/photos/alice.jpg", "USER");
    }

    // ── Authorization header forwarding (Task 2.2) ────────────────────────────

    @Test
    @DisplayName("Original Authorization header must be forwarded to downstream service")
    void givenValidToken_whenFilter_thenAuthorizationHeaderForwardedDownstream() {
        String bearerHeader = "Bearer " + validToken;
        MockServerWebExchange exchange = exchangeWithAuth("/api/v1/catalog/songs", bearerHeader);
        AtomicReference<ServerWebExchange> captured = new AtomicReference<>();
        GatewayFilterChain chain = captureChain(captured);

        applyFilter(exchange, chain);

        ServerWebExchange downstream = captured.get();
        assertThat(downstream).isNotNull();
        String forwardedAuth = downstream.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        assertThat(forwardedAuth)
                .as("Authorization header must be present downstream for zero-trust re-validation")
                .isNotNull()
                .isEqualTo(bearerHeader);
    }

    @Test
    @DisplayName("X-User-Id header must be set to JWT subject")
    void givenValidToken_whenFilter_thenXUserIdSetFromJwtSubject() {
        MockServerWebExchange exchange = exchangeWithAuth("/api/v1/catalog/songs", "Bearer " + validToken);
        AtomicReference<ServerWebExchange> captured = new AtomicReference<>();

        applyFilter(exchange, captureChain(captured));

        assertThat(captured.get().getRequest().getHeaders().getFirst("X-User-Id"))
                .isEqualTo("user-uuid-123");
    }

    @Test
    @DisplayName("X-User-Email header must be set from JWT email claim")
    void givenValidToken_whenFilter_thenXUserEmailSetFromClaims() {
        MockServerWebExchange exchange = exchangeWithAuth("/api/v1/catalog/songs", "Bearer " + validToken);
        AtomicReference<ServerWebExchange> captured = new AtomicReference<>();

        applyFilter(exchange, captureChain(captured));

        assertThat(captured.get().getRequest().getHeaders().getFirst("X-User-Email"))
                .isEqualTo("alice@example.com");
    }

    @Test
    @DisplayName("X-User-Role header must be set from JWT role claim")
    void givenValidToken_whenFilter_thenXUserRoleSetFromClaims() {
        MockServerWebExchange exchange = exchangeWithAuth("/api/v1/catalog/songs", "Bearer " + validToken);
        AtomicReference<ServerWebExchange> captured = new AtomicReference<>();

        applyFilter(exchange, captureChain(captured));

        assertThat(captured.get().getRequest().getHeaders().getFirst("X-User-Role"))
                .isEqualTo("USER");
    }

    // ── Rejection cases ───────────────────────────────────────────────────────

    @Test
    @DisplayName("Missing Authorization header → 401, chain never invoked")
    void givenNoAuthHeader_whenFilter_thenReturns401() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/v1/catalog/songs").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        GatewayFilterChain chain = mock(GatewayFilterChain.class);

        applyFilter(exchange, chain);

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(chain, never()).filter(any());
    }

    @Test
    @DisplayName("Authorization header without Bearer prefix → 401")
    void givenNonBearerHeader_whenFilter_thenReturns401() {
        MockServerWebExchange exchange = exchangeWithAuth("/api/v1/catalog/songs", "Basic dXNlcjpwYXNz");
        GatewayFilterChain chain = mock(GatewayFilterChain.class);

        applyFilter(exchange, chain);

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(chain, never()).filter(any());
    }

    @Test
    @DisplayName("Invalid / tampered JWT → 401, chain never invoked")
    void givenInvalidToken_whenFilter_thenReturns401() {
        MockServerWebExchange exchange = exchangeWithAuth("/api/v1/catalog/songs", "Bearer invalid.jwt.token");
        GatewayFilterChain chain = mock(GatewayFilterChain.class);

        applyFilter(exchange, chain);

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(chain, never()).filter(any());
    }

    @Test
    @DisplayName("Expired JWT → 401, chain never invoked")
    void givenExpiredToken_whenFilter_thenReturns401() {
        String expiredToken = Jwts.builder()
                .subject("user-expired")
                .claim("email", "x@example.com")
                .issuedAt(new Date(System.currentTimeMillis() - 120_000))
                .expiration(new Date(System.currentTimeMillis() - 60_000))  // expired 1 min ago
                .signWith(keyPair.getPrivate())
                .compact();

        MockServerWebExchange exchange = exchangeWithAuth("/api/v1/catalog/songs", "Bearer " + expiredToken);
        GatewayFilterChain chain = mock(GatewayFilterChain.class);

        applyFilter(exchange, chain);

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(chain, never()).filter(any());
    }

    @Test
    @DisplayName("JWT signed with wrong key → 401, chain never invoked")
    void givenTokenSignedWithWrongKey_whenFilter_thenReturns401() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair wrongPair = generator.generateKeyPair();

        String wrongKeyToken = Jwts.builder()
                .subject("hacker")
                .claim("email", "hacker@evil.com")
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(wrongPair.getPrivate())
                .compact();

        MockServerWebExchange exchange = exchangeWithAuth("/api/v1/catalog/songs", "Bearer " + wrongKeyToken);
        GatewayFilterChain chain = mock(GatewayFilterChain.class);

        applyFilter(exchange, chain);

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(chain, never()).filter(any());
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private String buildToken(String subject, String email, String displayName,
                              String photoUrl, String role) {
        return Jwts.builder()
                .subject(subject)
                .claim("email", email)
                .claim("displayName", displayName)
                .claim("profilePhotoUrl", photoUrl)
                .claim("role", role)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(keyPair.getPrivate())
                .compact();
    }

    private MockServerWebExchange exchangeWithAuth(String path, String authHeader) {
        MockServerHttpRequest request = MockServerHttpRequest
                .get(path)
                .header(HttpHeaders.AUTHORIZATION, authHeader)
                .build();
        return MockServerWebExchange.from(request);
    }

    private GatewayFilterChain captureChain(AtomicReference<ServerWebExchange> ref) {
        GatewayFilterChain chain = mock(GatewayFilterChain.class);
        when(chain.filter(any())).thenAnswer(inv -> {
            ref.set(inv.getArgument(0));
            return Mono.empty();
        });
        return chain;
    }

    private void applyFilter(MockServerWebExchange exchange, GatewayFilterChain chain) {
        GatewayFilter gatewayFilter = filter.apply(new JwtAuthFilter.Config());
        gatewayFilter.filter(exchange, chain).block();
    }
}
