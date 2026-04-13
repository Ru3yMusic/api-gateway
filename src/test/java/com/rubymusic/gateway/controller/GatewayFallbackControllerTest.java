package com.rubymusic.gateway.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for GatewayFallbackController — no Spring context required.
 *
 * <p>Verifies that the circuit-breaker fallback endpoint returns the expected
 * 503 response with a structured body.
 */
class GatewayFallbackControllerTest {

    private final GatewayFallbackController controller = new GatewayFallbackController();

    @Test
    @DisplayName("fallback() returns HTTP 503 SERVICE_UNAVAILABLE")
    void givenFallbackTriggered_whenFallback_thenReturns503() {
        MockServerWebExchange exchange = exchangeFor("/api/v1/catalog/songs");

        ResponseEntity<Map<String, Object>> response = controller.fallback(exchange).block();

        assertThat(response).isNotNull();
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    @DisplayName("fallback() body contains status=503 and error field")
    void givenFallbackTriggered_whenFallback_thenBodyContainsStatusAndError() {
        MockServerWebExchange exchange = exchangeFor("/api/v1/catalog/songs");

        ResponseEntity<Map<String, Object>> response = controller.fallback(exchange).block();

        assertThat(response).isNotNull();
        Map<String, Object> body = response.getBody();
        assertThat(body).isNotNull()
                .containsKey("status")
                .containsKey("error")
                .containsKey("message")
                .containsKey("timestamp");
        assertThat(body.get("status")).isEqualTo(503);
        assertThat(body.get("error")).isEqualTo("Service Unavailable");
    }

    @Test
    @DisplayName("fallback() message is human-readable and non-empty")
    void givenFallbackTriggered_whenFallback_thenMessageIsNonEmpty() {
        MockServerWebExchange exchange = exchangeFor("/api/v1/playlists/42");

        ResponseEntity<Map<String, Object>> response = controller.fallback(exchange).block();

        assertThat(response).isNotNull();
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().get("message").toString()).isNotBlank();
    }

    @Test
    @DisplayName("fallback() timestamp is a parseable ISO-8601 instant")
    void givenFallbackTriggered_whenFallback_thenTimestampIsIso8601() {
        MockServerWebExchange exchange = exchangeFor("/api/v1/social/feed");

        ResponseEntity<Map<String, Object>> response = controller.fallback(exchange).block();

        assertThat(response).isNotNull();
        assertThat(response.getBody()).isNotNull();
        String timestamp = response.getBody().get("timestamp").toString();
        // Instant.parse throws DateTimeParseException if not valid ISO-8601 — asserting no throw
        assertThat(java.time.Instant.parse(timestamp)).isNotNull();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private MockServerWebExchange exchangeFor(String path) {
        return MockServerWebExchange.from(MockServerHttpRequest.get(path).build());
    }
}
