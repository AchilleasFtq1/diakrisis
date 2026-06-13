package com.cy.diakritis.ops.config;

import com.cy.diakritis.common.security.JwtService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Provides the shared {@link JwtService} (verify only here — ops-service never mints tokens; it
 * validates JWTs minted by iam-service against the same HS256 secret). The Spring Security filter
 * chain that consumes the JwtService lives in {@link SecurityConfig}.
 */
@Configuration
public class JwtConfig {

    @Bean
    JwtService jwtService(@Value("${diakrisis.jwt.secret}") String secret) {
        return new JwtService(secret);
    }
}
