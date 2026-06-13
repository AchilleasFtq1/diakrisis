package com.cy.demobank.client;

/**
 * Raised when a call to a Diakrisis service (IAM login or decision scoring) fails — network error,
 * a 4xx/5xx response, or an empty/invalid body. Carries a human-readable message for rendering on
 * the verdict page so a demo operator can see why the decision could not be obtained.
 */
public class DiakrisisClientException extends RuntimeException {

    public DiakrisisClientException(String message) {
        super(message);
    }

    public DiakrisisClientException(String message, Throwable cause) {
        super(message, cause);
    }
}
