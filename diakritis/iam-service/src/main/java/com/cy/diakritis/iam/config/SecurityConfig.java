package com.cy.diakritis.iam.config;

import com.cy.diakritis.common.security.JsonAccessDeniedHandler;
import com.cy.diakritis.common.security.JsonAuthenticationEntryPoint;
import com.cy.diakritis.common.security.JwtAuthenticationFilter;
import com.cy.diakritis.common.security.JwtService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * The iam-service Spring Security filter chain. Stateless (no sessions, no CSRF — this is a JSON API),
 * authenticating from the {@code Authorization: Bearer <jwt>} header via the shared
 * {@link JwtAuthenticationFilter}, placed ahead of {@link UsernamePasswordAuthenticationFilter}.
 *
 * <p>Authorization:
 * <ul>
 *   <li>{@code /auth/**}, the docs surface ({@code /swagger-ui/**}, {@code /swagger-ui.html},
 *       {@code /v3/api-docs/**}) and {@code /actuator/health} are public.</li>
 *   <li>{@code /admin/**} requires {@code ROLE_ADMIN}.</li>
 *   <li>everything else requires authentication.</li>
 * </ul>
 *
 * <p>{@code @EnableMethodSecurity} activates {@code @PreAuthorize} (used on the admin console). The
 * custom JSON entry point / access-denied handler return stable {@code 401 {error:UNAUTHENTICATED}} /
 * {@code 403 {error:FORBIDDEN}} bodies so security failures never surface as 500 and stay consistent
 * with the {@code @RestControllerAdvice} error envelope.
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    private static final String[] PUBLIC_PATHS = {
            "/auth/**",
            "/swagger-ui/**",
            "/swagger-ui.html",
            "/v3/api-docs/**",
            "/actuator/health"
    };

    @Bean
    AuthenticationEntryPoint authenticationEntryPoint() {
        return new JsonAuthenticationEntryPoint();
    }

    @Bean
    AccessDeniedHandler accessDeniedHandler() {
        return new JsonAccessDeniedHandler();
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http,
                                            JwtService jwtService,
                                            AuthenticationEntryPoint entryPoint,
                                            AccessDeniedHandler deniedHandler) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(PUBLIC_PATHS).permitAll()
                        .requestMatchers("/admin/**").hasRole("ADMIN")
                        .anyRequest().authenticated())
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(deniedHandler))
                .addFilterBefore(new JwtAuthenticationFilter(jwtService),
                        UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
