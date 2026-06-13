package com.cy.diakritis.bank.security;

/**
 * Thrown when a protected endpoint is reached without a valid authenticated principal. Mapped to
 * HTTP 401 by the controller advice.
 */
public class UnauthorizedException extends RuntimeException {

    public UnauthorizedException(String message) {
        super(message);
    }
}
