package com.cy.diakritis.common.security;

import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Validates the {@code Authorization: Bearer <jwt>} header, stashes the resolved
 * {@link AuthPrincipal} as the request attribute {@code "principal"}, and exposes the raw
 * bearer token via {@link BearerTokenHolder} for downstream forwarding.
 *
 * <p>Public paths ({@code /auth/login}, {@code /actuator/**}) bypass authentication. Invalid
 * tokens are rejected with {@code 401}; the absence of a token on a protected path leaves the
 * principal attribute unset so the controller layer can enforce authorization explicitly.
 */
public class JwtAuthFilter extends OncePerRequestFilter {

    public static final String PRINCIPAL_ATTRIBUTE = "principal";

    private static final String AUTH_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";
    private static final String PUBLIC_LOGIN = "/auth/login";
    private static final String PUBLIC_ACTUATOR_PREFIX = "/actuator/";

    private final JwtService jwtService;

    public JwtAuthFilter(JwtService jwtService) {
        if (jwtService == null) {
            throw new IllegalArgumentException("jwtService must not be null");
        }
        this.jwtService = jwtService;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();
        if (path == null) {
            path = request.getRequestURI();
        }
        return PUBLIC_LOGIN.equals(path) || (path != null && path.startsWith(PUBLIC_ACTUATOR_PREFIX));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String header = request.getHeader(AUTH_HEADER);
        try {
            if (header != null && header.startsWith(BEARER_PREFIX)) {
                String token = header.substring(BEARER_PREFIX.length()).trim();
                try {
                    AuthPrincipal principal = jwtService.verify(token);
                    request.setAttribute(PRINCIPAL_ATTRIBUTE, principal);
                    BearerTokenHolder.set(token);
                } catch (JwtException ex) {
                    response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid token");
                    return;
                }
            }
            filterChain.doFilter(request, response);
        } finally {
            BearerTokenHolder.clear();
        }
    }
}
