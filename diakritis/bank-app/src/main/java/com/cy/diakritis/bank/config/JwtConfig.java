package com.cy.diakritis.bank.config;

import com.cy.diakritis.common.security.JwtService;
import com.cy.diakritis.common.security.PasswordHasher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Provides the shared {@link JwtService} (mint + verify, same HS256 secret as decision-service) and
 * the {@link PasswordHasher} (BCrypt). The Spring Security filter chain that consumes the JwtService
 * lives in {@link SecurityConfig}; the legacy {@code FilterRegistrationBean<JwtAuthFilter>} has been
 * removed in favour of that full chain.
 */
@Configuration
public class JwtConfig {

    @Bean
    JwtService jwtService(@Value("${diakrisis.jwt.secret}") String secret) {
        return new JwtService(secret);
    }

    @Bean
    PasswordHasher passwordHasher() {
        return new PasswordHasher();
    }
}
