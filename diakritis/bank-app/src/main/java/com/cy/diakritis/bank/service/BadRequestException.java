package com.cy.diakritis.bank.service;

/**
 * Thrown when a request is well-formed but semantically invalid for the current account state
 * (e.g. breaking an already-broken deposit). Mapped to HTTP 422.
 */
public class BadRequestException extends RuntimeException {

    public BadRequestException(String message) {
        super(message);
    }
}
