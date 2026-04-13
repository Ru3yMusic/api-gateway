package com.rubymusic.gateway.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for GatewayRateLimitConfig.
 *
 * <p>Verifies that the {@code userKeyResolver} bean correctly resolves rate-limit
 * keys: by authenticated userId (from X-User-Id header set by JwtAuthFilter) when
 * available, falling back to the request IP address.
 *
 * <p>No Spring context — pure unit test instantiating the config class directly.
 */
class GatewayRateLimitConfigTest {

    private GatewayRateLimitConfig config;
    private KeyResolver keyResolver;

    @BeforeEach
    void setUp() {
        config = new GatewayRateLimitConfig();
        keyResolver = config.userKeyResolver();
    }

    @Test
    @DisplayName("When X-User-Id header is present, key resolver returns the user ID")
    void givenAuthenticatedRequest_whenResolveKey_thenReturnsUserId() {
        MockServerHttpRequest request = MockServerHttpRequest
                .get("/api/v1/catalog/songs")
                .header("X-User-Id", "user-uuid-abc-123")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        String key = keyResolver.resolve(exchange).block();

        assertThat(key).isEqualTo("user-uuid-abc-123");
    }

    @Test
    @DisplayName("When X-User-Id header is absent, key resolver falls back to remote IP")
    void givenUnauthenticatedRequest_whenResolveKey_thenFallsBackToIp() {
        MockServerHttpRequest request = MockServerHttpRequest
                .get("/api/v1/auth/login")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        String key = keyResolver.resolve(exchange).block();

        // MockServerHttpRequest has no remote address — resolver must not throw
        assertThat(key).isNotNull().isNotBlank();
    }

    @Test
    @DisplayName("When X-User-Id header is blank, key resolver falls back to IP")
    void givenBlankUserId_whenResolveKey_thenFallsBackToIp() {
        MockServerHttpRequest request = MockServerHttpRequest
                .get("/api/v1/catalog/songs")
                .header("X-User-Id", "   ")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        String key = keyResolver.resolve(exchange).block();

        assertThat(key).isNotNull().isNotEqualTo("   ");
    }

    @Test
    @DisplayName("Key resolver bean must not be null")
    void keyResolverBeanMustNotBeNull() {
        assertThat(keyResolver).isNotNull();
    }

    @Test
    @DisplayName("Different user IDs produce different rate-limit keys")
    void givenDifferentUsers_whenResolveKey_thenKeysDiffer() {
        MockServerHttpRequest req1 = MockServerHttpRequest.get("/api/v1/catalog/songs")
                .header("X-User-Id", "user-a").build();
        MockServerHttpRequest req2 = MockServerHttpRequest.get("/api/v1/catalog/songs")
                .header("X-User-Id", "user-b").build();

        String key1 = keyResolver.resolve(MockServerWebExchange.from(req1)).block();
        String key2 = keyResolver.resolve(MockServerWebExchange.from(req2)).block();

        assertThat(key1).isNotEqualTo(key2);
    }
}
