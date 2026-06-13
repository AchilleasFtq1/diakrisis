package com.cy.diakritis.iam.service;

/**
 * Thrown when a referenced user does not exist. Mapped to HTTP 404 by the controller advice.
 */
public class ResourceNotFoundException extends RuntimeException {

    public ResourceNotFoundException(String message) {
        super(message);
    }
}
