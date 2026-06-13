package com.cy.diakritis.decision.config;

import com.cy.diakritis.common.security.JwtAuthFilter;
import com.cy.diakritis.common.security.JwtService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Registers the shared {@link JwtService} (same HS256 secret as bank-app) and installs
 * {@link JwtAuthFilter} ahead of the dispatcher so every protected request carries a resolved
 * {@code principal} request attribute that the lifecycle controller reads for four-eyes checks.
 */
@Configuration
public class JwtConfig {

    @Bean
    JwtService jwtService(@Value("${diakrisis.jwt.secret}") String secret) {
        return new JwtService(secret);
    }

    @Bean
    FilterRegistrationBean<JwtAuthFilter> jwtAuthFilter(JwtService jwtService) {
        FilterRegistrationBean<JwtAuthFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new JwtAuthFilter(jwtService));
        registration.addUrlPatterns("/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }
}
