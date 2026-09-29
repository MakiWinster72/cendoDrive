package com.cendodrive.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import static org.junit.jupiter.api.Assertions.*;

class CorsConfigTest {
    private final CorsConfig config = new CorsConfig();

    @Test void defaultDoesNotAllowCrossOriginRequests() {
        var cors = config.corsConfigurationSource("").getCorsConfiguration(apiRequest());
        assertNotNull(cors);
        assertNull(cors.checkOrigin("https://untrusted.example"));
        assertEquals(Boolean.FALSE, cors.getAllowCredentials());
    }

    @Test void onlyConfiguredExactOriginsAreAllowed() {
        var cors = config.corsConfigurationSource("https://app.example, https://admin.example")
                .getCorsConfiguration(apiRequest());
        assertNotNull(cors);
        assertEquals("https://app.example", cors.checkOrigin("https://app.example"));
        assertNull(cors.checkOrigin("https://evil.example"));
        assertTrue(cors.getAllowedHeaders().contains("Authorization"));
    }

    private static MockHttpServletRequest apiRequest() {
        return new MockHttpServletRequest("OPTIONS", "/api/auth/login");
    }
}
