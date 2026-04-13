package com.rubymusic.gateway.config;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;

/**
 * Gateway rate-limiting configuration.
 *
 * <p>Provides a {@link KeyResolver} bean that the {@code RequestRateLimiter} filter
 * uses to bucket requests:
 * <ul>
 *   <li>Authenticated requests (after {@code JwtAuthFilter}) are bucketed by
 *       {@code X-User-Id}, so each user gets their own independent rate limit.</li>
 *   <li>Unauthenticated requests (e.g. {@code /api/v1/auth/**}) fall back to the
 *       remote IP address.</li>
 * </ul>
 *
 * <p>The actual replenish rate and burst capacity are configured in
 * {@code config-server/config/api-gateway.yml} via
 * {@code spring.cloud.gateway.default-filters[RequestRateLimiter]} and are
 * environment-parameterized so they can differ between dev, staging, and prod.
 */
@Configuration
public class GatewayRateLimitConfig {

    /**
     * Resolves the rate-limit bucket key for a given request.
     *
     * <p>The bean name {@code userKeyResolver} is referenced in {@code api-gateway.yml}
     * via SpEL: {@code key-resolver: "#{@userKeyResolver}"}.
     */
    @Bean
    public KeyResolver userKeyResolver() {
        return exchange -> {
            // X-User-Id is injected by JwtAuthFilter after successful token validation.
            String userId = exchange.getRequest().getHeaders().getFirst("X-User-Id");
            if (userId != null && !userId.isBlank()) {
                return Mono.just(userId);
            }

            // Fall back to client IP for unauthenticated routes (login, register, etc.)
            String ip = exchange.getRequest().getRemoteAddress() != null
                    ? exchange.getRequest().getRemoteAddress().getAddress().getHostAddress()
                    : "unknown";
            return Mono.just(ip);
        };
    }
}
