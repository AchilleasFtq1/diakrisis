package com.cy.diakritis.decision.web.error;

/** Signals a missing resource → HTTP 404. */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }
}
