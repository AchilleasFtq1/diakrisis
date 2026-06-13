package com.cy.diakritis.gateway.config;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.util.List;

/**
 * Global CORS for the gateway. Browser apps (the Vite dev UI on :5173 and the demo-bank on :9000, on
 * both {@code localhost} and {@code 127.0.0.1}) call the gateway cross-origin, so it must echo the
 * proper CORS headers and answer preflight {@code OPTIONS} requests.
 *
 * <p>The {@link CorsFilter} is registered at {@link Ordered#HIGHEST_PRECEDENCE} so it runs BEFORE the
 * edge-auth filter — preflight requests are answered (and CORS headers attached to 401/403 responses)
 * even when authentication fails.
 */
@Configuration
public class CorsConfig {

    /**
     * Allowed browser origins. Origins are matched by exact pattern (scheme + host + port). Both the
     * {@code localhost} and {@code 127.0.0.1} variants are listed because browsers treat them as
     * distinct origins.
     */
    private static final List<String> ALLOWED_ORIGINS = List.of(
            "http://localhost:5173",
            "http://127.0.0.1:5173",
            "http://localhost:9000",
            "http://127.0.0.1:9000"
    );

    private static final long PREFLIGHT_MAX_AGE_SECONDS = 3600L;

    @Bean
    FilterRegistrationBean<CorsFilter> corsFilter() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(ALLOWED_ORIGINS);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS", "HEAD"));
        // Authorization (Bearer) + Content-Type are the headers the SPAs send.
        config.setAllowedHeaders(List.of(HttpHeaders.AUTHORIZATION, HttpHeaders.CONTENT_TYPE));
        config.setAllowCredentials(true);
        config.setMaxAge(PREFLIGHT_MAX_AGE_SECONDS);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);

        FilterRegistrationBean<CorsFilter> registration = new FilterRegistrationBean<>(new CorsFilter(source));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }
}
