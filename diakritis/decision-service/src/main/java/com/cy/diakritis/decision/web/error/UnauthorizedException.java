package com.cy.diakritis.decision.web.error;

/** Signals a missing or unusable authentication principal → HTTP 401. */
public class UnauthorizedException extends RuntimeException {

    public UnauthorizedException(String message) {
        super(message);
    }
}
