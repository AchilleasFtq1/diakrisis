package com.cy.diakritis.iam.service;

/**
 * Thrown when a request is well-formed syntactically but semantically invalid (e.g. an unknown role
 * literal, a blank required field). Mapped to HTTP 422 by the controller advice.
 */
public class BadRequestException extends RuntimeException {

    public BadRequestException(String message) {
        super(message);
    }
}
