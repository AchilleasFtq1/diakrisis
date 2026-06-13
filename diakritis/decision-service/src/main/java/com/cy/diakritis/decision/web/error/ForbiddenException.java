package com.cy.diakritis.decision.web.error;

/**
 * Signals an authorization failure (e.g. four-eyes self-approval) → HTTP 403. Carries a stable
 * {@code error} code surfaced verbatim in the response body (e.g. {@code SELF_APPROVAL_FORBIDDEN}).
 */
public class ForbiddenException extends RuntimeException {

    private final String errorCode;

    public ForbiddenException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
