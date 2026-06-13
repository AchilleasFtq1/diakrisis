package com.cy.diakritis.common.security;

import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Spring Security {@link OncePerRequestFilter} that authenticates a request from its
 * {@code Authorization: Bearer <jwt>} header. Registered ahead of
 * {@code UsernamePasswordAuthenticationFilter} in each app's {@code SecurityFilterChain}.
 *
 * <p>On a valid token it sets the {@link SecurityContextHolder} with a
 * {@link UsernamePasswordAuthenticationToken} whose principal is the resolved {@link AuthPrincipal}
 * and whose single authority is {@code ROLE_<role>} (e.g. {@code ROLE_CUSTOMER}, {@code ROLE_ADMIN}),
 * so {@code authorizeHttpRequests(...).hasRole("ADMIN")} and {@code @PreAuthorize("hasRole('ADMIN')")}
 * work. It additionally preserves the two legacy carriers the existing controllers/services depend on:
 * the {@code "principal"} request attribute (read by {@code CurrentUser}/{@code CurrentPrincipal} for
 * account-binding and the four-eyes self-approval check) and the {@link BearerTokenHolder} thread-local
 * (so bank-app forwards the caller's credential to decision-service). This keeps the golden-path
 * semantics intact while delegating coarse authorization to the security chain.
 *
 * <p>An invalid / malformed / expired token does NOT write a response here: the context is simply left
 * unauthenticated and the chain's {@code AuthenticationEntryPoint} renders the stable 401 JSON. This
 * guarantees a known-shaped bad request can never escape as a 500 (contract CI-8).
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String AUTH_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";
    private static final String ROLE_PREFIX = "ROLE_";

    private final JwtService jwtService;

    public JwtAuthenticationFilter(JwtService jwtService) {
        if (jwtService == null) {
            throw new IllegalArgumentException("jwtService must not be null");
        }
        this.jwtService = jwtService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String header = request.getHeader(AUTH_HEADER);
        try {
            if (header != null && header.startsWith(BEARER_PREFIX)
                    && SecurityContextHolder.getContext().getAuthentication() == null) {
                String token = header.substring(BEARER_PREFIX.length()).trim();
                authenticate(token, request);
            }
            filterChain.doFilter(request, response);
        } finally {
            BearerTokenHolder.clear();
        }
    }

    private void authenticate(String token, HttpServletRequest request) {
        try {
            AuthPrincipal principal = jwtService.verify(token);
            var authority = new SimpleGrantedAuthority(ROLE_PREFIX + principal.role().name());
            var authentication = new UsernamePasswordAuthenticationToken(
                    principal, token, List.of(authority));
            SecurityContextHolder.getContext().setAuthentication(authentication);
            // Legacy carriers the existing controllers/services still read.
            request.setAttribute(JwtAuthFilter.PRINCIPAL_ATTRIBUTE, principal);
            BearerTokenHolder.set(token);
        } catch (JwtException ex) {
            // Leave the context unauthenticated; the entry point renders the 401 if the path is
            // protected. A malformed token must never short-circuit into a 500.
            SecurityContextHolder.clearContext();
        }
    }
}
