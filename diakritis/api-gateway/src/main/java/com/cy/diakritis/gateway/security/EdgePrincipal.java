package com.cy.diakritis.gateway.security;

/**
 * The minimal principal extracted from a verified edge JWT: the subject (user id) and the role claim
 * (e.g. {@code CUSTOMER}, {@code OPS}, {@code APPROVER}, {@code ADMIN}). {@code role} may be null if
 * the token carries no role claim — callers that gate on a specific role must treat null as "not that
 * role".
 *
 * @param subject the token subject ({@code sub}); may be null for malformed-but-signed tokens
 * @param role    the role claim value; may be null
 */
public record EdgePrincipal(String subject, String role) {

    /**
     * @param expected the role name to test against (case-insensitive)
     * @return true iff this principal's role equals {@code expected}
     */
    public boolean hasRole(String expected) {
        return role != null && role.equalsIgnoreCase(expected);
    }
}
