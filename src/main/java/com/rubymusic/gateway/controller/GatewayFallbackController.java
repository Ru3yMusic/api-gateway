package com.rubymusic.gateway.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Map;

/**
 * Fallback endpoint invoked by the Resilience4j CircuitBreaker filter when a
 * downstream service is unavailable or exceeds its failure-rate threshold.
 *
 * <p>All routes in {@code api-gateway.yml} forward to {@code /fallback} via
 * {@code fallbackUri: forward:/fallback}. This controller responds with a
 * structured 503 body so clients can distinguish a circuit-open response from
 * a real upstream error.
 *
 * <p>The controller is reactive (WebFlux) — it must NOT use blocking I/O.
 */
@RestController
@Slf4j
public class GatewayFallbackController {

    @RequestMapping("/fallback")
    public Mono<ResponseEntity<Map<String, Object>>> fallback(ServerWebExchange exchange) {
        log.warn("Circuit breaker fallback triggered for: {}",
                exchange.getRequest().getURI());
        return Mono.just(ResponseEntity
                .status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of(
                        "status", 503,
                        "error", "Service Unavailable",
                        "message", "The service is temporarily unavailable. Please try again later.",
                        "timestamp", Instant.now().toString()
                )));
    }
}
