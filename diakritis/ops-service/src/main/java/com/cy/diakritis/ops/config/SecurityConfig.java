package com.cy.diakritis.ops.config;

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
 * The ops-service Spring Security filter chain. Stateless (no sessions, no CSRF — this is a JSON API),
 * authenticating from the {@code Authorization: Bearer <jwt>} header via the shared
 * {@link JwtAuthenticationFilter}, placed ahead of {@link UsernamePasswordAuthenticationFilter}.
 *
 * <p>Authorization:
 * <ul>
 *   <li>the docs surface ({@code /swagger-ui/**}, {@code /swagger-ui.html}, {@code /v3/api-docs/**})
 *       and {@code /actuator/health} are public.</li>
 *   <li>{@code /ops/**} requires one of {@code ROLE_OPS} / {@code ROLE_APPROVER} (the analyst roles)
 *       or {@code ROLE_ADMIN} (superuser); the controllers additionally assert the same constraint
 *       from the request principal.</li>
 *   <li>everything else requires authentication.</li>
 * </ul>
 *
 * <p>The custom JSON entry point / access-denied handler return stable
 * {@code 401 {error:UNAUTHENTICATED}} / {@code 403 {error:FORBIDDEN}} bodies so security failures
 * never surface as 500 and stay consistent with the {@code @RestControllerAdvice} error envelope.
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    private static final String[] PUBLIC_PATHS = {
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
                        .requestMatchers("/ops/**").hasAnyRole("OPS", "APPROVER", "ADMIN")
                        .anyRequest().authenticated())
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(deniedHandler))
                .addFilterBefore(new JwtAuthenticationFilter(jwtService),
                        UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
