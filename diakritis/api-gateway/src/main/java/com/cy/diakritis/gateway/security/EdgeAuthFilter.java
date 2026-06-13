package com.cy.diakritis.gateway.security;

import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Edge authentication filter — the gateway's first line of defense, run before the Spring Cloud
 * Gateway routing handler forwards anything downstream.
 *
 * <p>Behaviour:
 * <ul>
 *   <li>CORS preflight ({@code OPTIONS}) requests pass through untouched so the CORS layer can answer
 *       them — they never carry an {@code Authorization} header.</li>
 *   <li>Public paths (login/register/refresh, the gateway's own Swagger + actuator health) bypass the
 *       token check entirely.</li>
 *   <li>Every other route requires a valid {@code Bearer} token; a missing/invalid/expired token is
 *       rejected with a JSON {@code 401}.</li>
 *   <li>{@code /admin/**} additionally requires the {@code ADMIN} role; a valid non-admin token is
 *       rejected with a JSON {@code 403}.</li>
 * </ul>
 *
 * <p>Ordered just after the CORS filter (which Spring registers at {@link org.springframework.core.Ordered#HIGHEST_PRECEDENCE})
 * so preflight handling and CORS response headers are applied even on rejected requests.
 */
@Component
@Order(EdgeAuthFilter.ORDER)
public class EdgeAuthFilter extends OncePerRequestFilter {

    /** Run right after Spring's CORS filter (HIGHEST_PRECEDENCE) so 401/403 responses still get CORS headers. */
    public static final int ORDER = org.springframework.core.Ordered.HIGHEST_PRECEDENCE + 10;

    private static final Logger log = LoggerFactory.getLogger(EdgeAuthFilter.class);

    private static final String BEARER_PREFIX = "Bearer ";
    private static final String ADMIN_PREFIX = "/admin";
    private static final String ROLE_ADMIN = "ADMIN";

    /**
     * Paths reachable with NO token at the edge. Login/register/refresh must be open so users can
     * obtain a token; the gateway's own API-docs/Swagger and the actuator health probe are open so the
     * UI and the container healthcheck work pre-auth. Everything else is protected.
     */
    private static final List<String> PUBLIC_PATTERNS = List.of(
            "/auth/login",
            "/auth/register",
            "/auth/refresh",
            "/swagger-ui",
            "/swagger-ui/**",
            "/swagger-ui.html",
            "/v3/api-docs",
            "/v3/api-docs/**",
            "/actuator/health",
            "/actuator/health/**"
    );

    private final EdgeJwtVerifier verifier;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    public EdgeAuthFilter(EdgeJwtVerifier verifier) {
        this.verifier = verifier;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        // CORS preflight: never carries a token; let the CORS layer answer it.
        if (HttpMethod.OPTIONS.matches(request.getMethod())) {
            chain.doFilter(request, response);
            return;
        }

        String path = request.getRequestURI();
        if (isPublic(path)) {
            chain.doFilter(request, response);
            return;
        }

        String authHeader = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authHeader == null || !authHeader.startsWith(BEARER_PREFIX)) {
            writeError(response, HttpStatus.UNAUTHORIZED, "Missing or malformed Authorization header");
            return;
        }

        String token = authHeader.substring(BEARER_PREFIX.length()).trim();
        EdgePrincipal principal;
        try {
            principal = verifier.verify(token);
        } catch (JwtException ex) {
            // Do not leak token details to the caller; log at debug for troubleshooting.
            log.debug("Edge JWT rejected for {} {}: {}", request.getMethod(), path, ex.getMessage());
            writeError(response, HttpStatus.UNAUTHORIZED, "Invalid or expired token");
            return;
        }

        // Coarse role gate at the edge for the admin console; iam-service re-checks ADMIN itself.
        if (isAdminPath(path) && !principal.hasRole(ROLE_ADMIN)) {
            writeError(response, HttpStatus.FORBIDDEN, "Admin role required");
            return;
        }

        chain.doFilter(request, response);
    }

    private boolean isPublic(String path) {
        for (String pattern : PUBLIC_PATTERNS) {
            if (pathMatcher.match(pattern, path)) {
                return true;
            }
        }
        return false;
    }

    private boolean isAdminPath(String path) {
        return path.equals(ADMIN_PREFIX) || path.startsWith(ADMIN_PREFIX + "/");
    }

    private void writeError(HttpServletResponse response, HttpStatus status, String message) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        // Hand-built JSON keeps the filter free of a Jackson dependency and matches the product's
        // {error, message, status} error envelope shape.
        String body = "{\"error\":\"" + status.getReasonPhrase() + "\",\"message\":\""
                + escape(message) + "\",\"status\":" + status.value() + "}";
        response.getWriter().write(body);
    }

    private static String escape(String raw) {
        return raw.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
