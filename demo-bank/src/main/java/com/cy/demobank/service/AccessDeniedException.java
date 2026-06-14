package com.cy.demobank.service;

/**
 * Thrown when an authenticated customer attempts to read or move money on an account, deposit, or
 * pending payment they do not own. Mapped to HTTP 403 (Forbidden) by the controllers so the engine's
 * horizontal-authorization boundary is enforced independently of any session presence check.
 *
 * <p>Deliberately a {@code RuntimeException} rather than a subclass of {@link IllegalArgumentException}:
 * an ownership failure is an authorization fault (403), not a bad-input fault (400), and must not be
 * funneled into the generic validation handler.
 */
public class AccessDeniedException extends RuntimeException {

    public AccessDeniedException(String message) {
        super(message);
    }
}
