package com.cy.diakritis.iam.security;

/**
 * Thrown when credentials are missing or invalid (bad password, unknown user, invalid/expired/revoked
 * refresh token). Mapped to HTTP 401 by the controller advice.
 */
public class UnauthorizedException extends RuntimeException {

    public UnauthorizedException(String message) {
        super(message);
    }
}
