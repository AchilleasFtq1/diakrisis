package com.cy.diakritis.ops.security;

/**
 * Thrown when an authenticated principal lacks the role required for an operation. Mapped to
 * HTTP 403 by the controller advice.
 */
public class ForbiddenException extends RuntimeException {

    public ForbiddenException(String message) {
        super(message);
    }
}
