package com.cy.diakritis.bank.service;

/**
 * Thrown when a request conflicts with current state — e.g. registering a username that already
 * exists. Mapped to HTTP 409 by the controller advice.
 */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
