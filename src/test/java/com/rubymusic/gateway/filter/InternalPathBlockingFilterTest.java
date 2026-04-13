package com.rubymusic.gateway.filter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * TDD — RED phase: these tests must fail until InternalPathBlockingFilter is created.
 *
 * <p>Verifies that any external request matching /api/internal/** is blocked with
 * 403 Forbidden before reaching route predicates, while public /api/v1/** routes
 * pass through normally.
 */
class InternalPathBlockingFilterTest {

    private InternalPathBlockingFilter filter;

    @BeforeEach
    void setUp() {
        filter = new InternalPathBlockingFilter();
    }

    // ── 403 cases ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("/api/internal/v1/auth/service-token → 403, chain never invoked")
    void givenServiceTokenPath_whenFilter_thenReturns403AndChainSkipped() {
        MockServerHttpRequest request = MockServerHttpRequest
                .post("/api/internal/v1/auth/service-token")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        GatewayFilterChain chain = mock(GatewayFilterChain.class);

        filter.filter(exchange, chain).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        verify(chain, never()).filter(any());
    }

    @Test
    @DisplayName("/api/internal/v1/users/{id} → 403, chain never invoked")
    void givenInternalUsersPath_whenFilter_thenReturns403() {
        MockServerHttpRequest request = MockServerHttpRequest
                .get("/api/internal/v1/users/550e8400-e29b-41d4-a716-446655440000")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        GatewayFilterChain chain = mock(GatewayFilterChain.class);

        filter.filter(exchange, chain).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        verify(chain, never()).filter(any());
    }

    @Test
    @DisplayName("/api/internal/v1/users/batch → 403, chain never invoked")
    void givenInternalBatchPath_whenFilter_thenReturns403() {
        MockServerHttpRequest request = MockServerHttpRequest
                .get("/api/internal/v1/users/batch?ids=id1,id2")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        GatewayFilterChain chain = mock(GatewayFilterChain.class);

        filter.filter(exchange, chain).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        verify(chain, never()).filter(any());
    }

    @Test
    @DisplayName("/api/internal/** (any sub-path) → 403")
    void givenAnyInternalSubPath_whenFilter_thenReturns403() {
        MockServerHttpRequest request = MockServerHttpRequest
                .get("/api/internal/v2/some/new/endpoint")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        GatewayFilterChain chain = mock(GatewayFilterChain.class);

        filter.filter(exchange, chain).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        verify(chain, never()).filter(any());
    }

    // ── pass-through cases ────────────────────────────────────────────────────

    @Test
    @DisplayName("/api/v1/catalog/songs → passes through, chain invoked")
    void givenPublicCatalogPath_whenFilter_thenChainInvoked() {
        MockServerHttpRequest request = MockServerHttpRequest
                .get("/api/v1/catalog/songs")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        GatewayFilterChain chain = mock(GatewayFilterChain.class);
        when(chain.filter(any())).thenReturn(Mono.empty());

        filter.filter(exchange, chain).block();

        verify(chain).filter(any());
        assertThat(exchange.getResponse().getStatusCode()).isNotEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("/api/v1/auth/login → passes through")
    void givenAuthLoginPath_whenFilter_thenChainInvoked() {
        MockServerHttpRequest request = MockServerHttpRequest
                .post("/api/v1/auth/login")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        GatewayFilterChain chain = mock(GatewayFilterChain.class);
        when(chain.filter(any())).thenReturn(Mono.empty());

        filter.filter(exchange, chain).block();

        verify(chain).filter(any());
    }

    @Test
    @DisplayName("/socket.io/** → passes through")
    void givenSocketIoPath_whenFilter_thenChainInvoked() {
        MockServerHttpRequest request = MockServerHttpRequest
                .get("/socket.io/poll?EIO=4")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        GatewayFilterChain chain = mock(GatewayFilterChain.class);
        when(chain.filter(any())).thenReturn(Mono.empty());

        filter.filter(exchange, chain).block();

        verify(chain).filter(any());
    }

    // ── ordering ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Filter order must be negative (execute before route predicates)")
    void filterOrderMustBeBeforeRouting() {
        assertThat(filter.getOrder()).isLessThan(0);
    }
}
