package com.cy.diakritis.decision.web.error;

/**
 * Signals an illegal lifecycle transition or a guarded operation that is not currently permitted
 * → HTTP 409. Carries a stable {@code error} code surfaced verbatim in the response body
 * (e.g. {@code LOCKED_PRE_EXPIRY}).
 */
public class ConflictException extends RuntimeException {

    private final String errorCode;

    public ConflictException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
