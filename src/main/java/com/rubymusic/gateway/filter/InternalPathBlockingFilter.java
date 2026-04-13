package com.rubymusic.gateway.filter;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Global filter that blocks any external request targeting internal service paths.
 *
 * <p>Any request matching {@code /api/internal/**} is immediately rejected with
 * {@code 403 Forbidden} before it reaches route predicates or any downstream service.
 * Internal services communicate with each other via the internal network, bypassing
 * the public API Gateway entirely — so no legitimate external traffic should ever
 * arrive at these paths.
 *
 * <p>This filter runs at {@link Ordered#HIGHEST_PRECEDENCE} + 1, ensuring it executes
 * before all other filters and before Spring Cloud Gateway evaluates route predicates.
 */
@Slf4j
@Component
public class InternalPathBlockingFilter implements GlobalFilter, Ordered {

    private static final String INTERNAL_PATH_PREFIX = "/api/internal/";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();

        if (path.startsWith(INTERNAL_PATH_PREFIX)) {
            log.warn("Blocked external access attempt to internal path: {} {}",
                    exchange.getRequest().getMethod(), path);
            exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
            return exchange.getResponse().setComplete();
        }

        return chain.filter(exchange);
    }

    /**
     * Runs just after {@link Ordered#HIGHEST_PRECEDENCE} so it fires before all
     * route predicates and other gateway filters. Negative value guarantees
     * pre-routing execution.
     */
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 1;
    }
}
