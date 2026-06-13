package com.cy.diakritis.bank.security;

import com.cy.diakritis.common.security.AuthPrincipal;
import com.cy.diakritis.common.security.JwtAuthFilter;
import com.cy.diakritis.common.security.Role;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Resolves and authorizes the request principal stashed by {@link JwtAuthFilter}. Centralizes the
 * "no principal -> 401" and "wrong role -> 403" checks so controllers stay declarative.
 */
@Component
public class CurrentUser {

    /**
     * @return the authenticated principal
     * @throws UnauthorizedException if no valid principal is present on the request
     */
    public AuthPrincipal require(HttpServletRequest request) {
        Object attribute = request.getAttribute(JwtAuthFilter.PRINCIPAL_ATTRIBUTE);
        if (attribute instanceof AuthPrincipal principal) {
            return principal;
        }
        throw new UnauthorizedException("Authentication required");
    }

    /**
     * @return the authenticated principal, asserting it holds one of {@code allowed} roles
     * @throws UnauthorizedException if unauthenticated
     * @throws ForbiddenException    if authenticated but not in {@code allowed}
     */
    public AuthPrincipal requireRole(HttpServletRequest request, Role... allowed) {
        AuthPrincipal principal = require(request);
        Set<Role> permitted = Set.of(allowed);
        if (!permitted.contains(principal.role())) {
            throw new ForbiddenException("Role " + principal.role() + " is not permitted for this operation");
        }
        return principal;
    }
}
