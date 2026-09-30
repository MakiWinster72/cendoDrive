package com.cendodrive.config;

import java.net.URI;
import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
public class CorsConfig {
    @Bean
    CorsConfigurationSource corsConfigurationSource(@Value("${app.cors.allowed-origins:}") String origins) {
        List<String> allowed = Arrays.stream(origins.split(","))
                .map(String::trim).filter(origin -> !origin.isEmpty())
                .map(CorsConfig::validateOrigin).toList();
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(allowed);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        config.setAllowCredentials(false);
        config.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }

    private static String validateOrigin(String origin) {
        try {
            URI uri = URI.create(origin);
            if (("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
                    && uri.getHost() != null && (uri.getRawPath() == null || uri.getRawPath().isEmpty())
                    && uri.getRawQuery() == null && uri.getRawFragment() == null
                    && uri.getUserInfo() == null) return origin;
        } catch (IllegalArgumentException ignored) { /* reject malformed origins */ }
        throw new IllegalArgumentException("CORS_ALLOWED_ORIGINS must contain exact HTTP(S) origins");
    }
}
