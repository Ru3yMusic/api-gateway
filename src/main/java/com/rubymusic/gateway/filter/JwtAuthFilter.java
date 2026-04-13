package com.rubymusic.gateway.filter;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * Gateway filter that validates the JWT Bearer token (RS256) on every protected route.
 *
 * <p>On success it forwards:
 * <ul>
 *   <li>{@code X-User-Id}           — JWT subject (user UUID)</li>
 *   <li>{@code X-User-Email}        — email claim</li>
 *   <li>{@code X-Display-Name}      — displayName claim</li>
 *   <li>{@code X-Profile-Photo-Url} — profilePhotoUrl claim</li>
 *   <li>{@code X-User-Role}         — role claim (USER or ADMIN)</li>
 * </ul>
 * Downstream services trust these headers and never re-validate the token.
 *
 * <p>The public key is loaded from the {@code jwt.public-key} property
 * (Base64-encoded X.509 DER bytes, without PEM headers).
 */
@Slf4j
@Component
public class JwtAuthFilter extends AbstractGatewayFilterFactory<JwtAuthFilter.Config> {

    @Value("${jwt.public-key}")
    private String publicKeyBase64;

    private PublicKey publicKey;

    public JwtAuthFilter() {
        super(Config.class);
    }

    @PostConstruct
    public void init() throws Exception {
        String stripped = publicKeyBase64
                .replaceAll("-----[A-Z ]+-----", "")
                .replaceAll("\\s+", "");
        byte[] decoded = Base64.getDecoder().decode(stripped);
        publicKey = KeyFactory.getInstance("RSA")
                .generatePublic(new X509EncodedKeySpec(decoded));
        log.info("JwtAuthFilter: RSA public key loaded successfully");
    }

    @Override
    public GatewayFilter apply(Config config) {
        return (exchange, chain) -> {
            String authHeader = exchange.getRequest()
                    .getHeaders()
                    .getFirst(HttpHeaders.AUTHORIZATION);

            if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                return onError(exchange, HttpStatus.UNAUTHORIZED,
                        "Missing or malformed Authorization header");
            }

            String token = authHeader.substring(7);

            try {
                Claims claims = Jwts.parser()
                        .verifyWith(publicKey)
                        .build()
                        .parseSignedClaims(token)
                        .getPayload();

                // Propagate identity to downstream services via internal headers.
                // NOTE (zero-trust): Spring's ServerHttpRequest.Builder copies ALL original
                // headers from the incoming request first (via addAll), then only replaces
                // headers that are explicitly set below. This means the original
                // "Authorization: Bearer <token>" header IS preserved and forwarded to every
                // downstream service so each service can independently re-validate the JWT —
                // the core requirement of the zero-trust architecture.
                String displayName = claims.get("displayName", String.class);
                String profilePhotoUrl = claims.get("profilePhotoUrl", String.class);
                String role = claims.get("role", String.class);
                ServerWebExchange mutated = exchange.mutate()
                        .request(r -> r
                                .header("X-User-Id", claims.getSubject())
                                .header("X-User-Email", claims.get("email", String.class))
                                .header("X-Display-Name", displayName != null ? displayName : "")
                                .header("X-Profile-Photo-Url", profilePhotoUrl != null ? profilePhotoUrl : "")
                                .header("X-User-Role", role != null ? role : "USER"))
                        .build();

                return chain.filter(mutated);

            } catch (JwtException e) {
                log.warn("JWT validation failed: {}", e.getMessage());
                return onError(exchange, HttpStatus.UNAUTHORIZED, "Invalid or expired token");
            }
        };
    }

    private Mono<Void> onError(ServerWebExchange exchange, HttpStatus status, String reason) {
        log.debug("Gateway auth rejected — {}: {}", status, reason);
        exchange.getResponse().setStatusCode(status);
        return exchange.getResponse().setComplete();
    }

    public static class Config {
        // No configuration properties needed
    }
}
