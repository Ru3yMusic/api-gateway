package com.rubymusic.gateway;

import com.rubymusic.gateway.filter.JwtAuthFilter;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

/**
 * Application context smoke test.
 *
 * <p>{@code JwtAuthFilter} is mocked to avoid loading a real RSA public key
 * in the test environment (the key is only available at runtime via Config Server).
 * All filter behaviour is covered by {@code JwtAuthFilterTest} (pure unit test).
 */
@SpringBootTest(
        // MOCK = MockReactiveWebApplicationContext — provides ServerProperties and
        // reactive auto-config without starting a real Netty server.
        // NONE would skip reactive web setup, breaking GatewayAutoConfiguration.
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "spring.cloud.config.import-check.enabled=false",
                "spring.cloud.config.enabled=false"
        }
)
class ApiGatewayApplicationTests {

    /**
     * Replaces the real JwtAuthFilter bean so its @PostConstruct RSA key loading
     * does not run during context startup in CI / local test runs.
     */
    @MockBean
    JwtAuthFilter jwtAuthFilter;

    @Test
    void contextLoads() {
    }
}
