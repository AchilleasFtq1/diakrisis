package com.cy.diakritis.iam.security;

/**
 * Thrown when an authenticated principal is denied an operation (e.g. authenticating into a disabled
 * account). Mapped to HTTP 403 by the controller advice.
 */
public class ForbiddenException extends RuntimeException {

    public ForbiddenException(String message) {
        super(message);
    }
}
