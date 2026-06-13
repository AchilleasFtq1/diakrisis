package com.cy.diakritis.decision.web;

import com.cy.diakritis.common.security.AuthPrincipal;
import com.cy.diakritis.common.security.JwtAuthFilter;
import jakarta.servlet.http.HttpServletRequest;

/**
 * Reads the {@link AuthPrincipal} that {@link JwtAuthFilter} stashed as a request attribute. Kept as
 * a small helper (rather than an argument resolver) so the controllers can decide per-endpoint
 * whether a principal is required.
 */
public final class CurrentPrincipal {

    private CurrentPrincipal() {
    }

    /** The resolved principal, or null if the request carried no (or no valid) bearer token. */
    public static AuthPrincipal from(HttpServletRequest request) {
        Object attribute = request.getAttribute(JwtAuthFilter.PRINCIPAL_ATTRIBUTE);
        return attribute instanceof AuthPrincipal principal ? principal : null;
    }
}
