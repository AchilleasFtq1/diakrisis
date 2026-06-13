package com.cy.diakritis.decision.web.error;

/** Signals a semantically invalid but well-formed request → HTTP 422. */
public class UnprocessableException extends RuntimeException {

    public UnprocessableException(String message) {
        super(message);
    }
}
