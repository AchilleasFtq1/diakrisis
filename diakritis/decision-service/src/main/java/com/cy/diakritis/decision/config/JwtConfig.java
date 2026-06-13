package com.cy.diakritis.decision.config;

import com.cy.diakritis.common.security.JwtService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Provides the shared {@link JwtService} (same HS256 secret as bank-app). The Spring Security filter
 * chain that validates tokens lives in {@link SecurityConfig}; the legacy
 * {@code FilterRegistrationBean<JwtAuthFilter>} has been removed in favour of that full chain.
 */
@Configuration
public class JwtConfig {

    @Bean
    JwtService jwtService(@Value("${diakrisis.jwt.secret}") String secret) {
        return new JwtService(secret);
    }
}
