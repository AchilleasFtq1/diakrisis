package com.cy.diakritis.bank.service;

/**
 * Thrown when a referenced account, payee, or deposit does not exist. Mapped to HTTP 404.
 */
public class ResourceNotFoundException extends RuntimeException {

    public ResourceNotFoundException(String message) {
        super(message);
    }
}
