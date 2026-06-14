package com.cy.diakritis.decision.web;

import com.cy.diakritis.decision.web.error.ConflictException;
import com.cy.diakritis.decision.web.error.ForbiddenException;
import com.cy.diakritis.decision.web.error.NotFoundException;
import com.cy.diakritis.decision.web.error.UnauthorizedException;
import com.cy.diakritis.decision.web.error.UnprocessableException;
import io.jsonwebtoken.JwtException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import software.amazon.awssdk.core.exception.SdkException;

import java.util.Map;

/**
 * Translates exceptions into stable HTTP responses for the decision API. A malformed or
 * semantically-invalid request is always a 4xx (contract CI-8); genuine server/infra faults surface as
 * 5xx so clients retry correctly and incidents are not masked:
 * <ul>
 *   <li>malformed JSON / bad enum literal → 400</li>
 *   <li>bean-validation failure / semantically invalid request → 422</li>
 *   <li>genuine bad-input argument validation → 422</li>
 *   <li>illegal lifecycle transition / pre-expiry lock → 409 (with a stable {@code error} code)</li>
 *   <li>invalid / missing token → 401</li>
 *   <li>four-eyes self-approval → 403 (with {@code SELF_APPROVAL_FORBIDDEN})</li>
 *   <li>unknown decision / resource → 404</li>
 *   <li>transient upstream/infra fault (AWS SDK / DynamoDB) → 503 (retryable)</li>
 *   <li>corrupt internal state (e.g. an unreadable stored decision) → 500</li>
 *   <li>anything else unforeseen → 500</li>
 * </ul>
 *
 * <p>CI-8 guarantees only that a malformed or semantically-invalid BODY is a 4xx (enforced by the
 * validation / parse / Unprocessable handlers); it does NOT mask a backend fault behind a 4xx — a
 * DynamoDB outage or a corrupt stored row correctly becomes a 5xx so well-behaved clients retry.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final String ERROR_KEY = "error";

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> handleValidation(MethodArgumentNotValidException ex) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .orElse("Validation failed");
        return body(HttpStatus.UNPROCESSABLE_ENTITY, detail);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, String>> handleUnreadable(HttpMessageNotReadableException ex) {
        // Malformed JSON or an invalid enum/type literal in the body is a bad request (400).
        return body(HttpStatus.BAD_REQUEST, "Malformed request body");
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Map<String, String>> handleMethod(HttpRequestMethodNotSupportedException ex) {
        return body(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    @ExceptionHandler(UnprocessableException.class)
    public ResponseEntity<Map<String, String>> handleUnprocessable(UnprocessableException ex) {
        return body(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleIllegalArgument(IllegalArgumentException ex) {
        return body(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage());
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<Map<String, String>> handleConflict(ConflictException ex) {
        return body(HttpStatus.CONFLICT, ex.getErrorCode());
    }

    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<Map<String, String>> handleUnauthorized(UnauthorizedException ex) {
        return body(HttpStatus.UNAUTHORIZED, ex.getMessage());
    }

    @ExceptionHandler(JwtException.class)
    public ResponseEntity<Map<String, String>> handleJwt(JwtException ex) {
        return body(HttpStatus.UNAUTHORIZED, "Invalid token");
    }

    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<Map<String, String>> handleForbidden(ForbiddenException ex) {
        return body(HttpStatus.FORBIDDEN, ex.getErrorCode());
    }

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<Map<String, String>> handleNotFound(NotFoundException ex) {
        return body(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(SdkException.class)
    public ResponseEntity<Map<String, String>> handleUpstream(SdkException ex) {
        // A transient AWS/DynamoDB transport or service fault is NOT the caller's fault: map it to a
        // retryable 503 so well-behaved clients retry and monitoring sees a real backend incident,
        // instead of a 422 that wrongly tells the caller the request was invalid.
        LOG.error("Upstream AWS/DynamoDB fault mapped to 503", ex);
        return body(HttpStatus.SERVICE_UNAVAILABLE, "Upstream temporarily unavailable");
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> handleIllegalState(IllegalStateException ex) {
        // Corrupt internal state — e.g. readStored()'s "Stored decision is unreadable" on a CORRUPT
        // previously-accepted row — is a server fault (500), never a client 422.
        LOG.error("Illegal internal state mapped to 500", ex);
        return body(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleUnexpected(Exception ex) {
        // Genuinely unforeseen failures are server faults (500), not client errors. CI-8 still holds:
        // malformed/semantically-invalid bodies are caught as 4xx by the handlers above before reaching
        // here. The cause is logged at ERROR for operators.
        LOG.error("Unhandled exception mapped to 500", ex);
        return body(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error");
    }

    private ResponseEntity<Map<String, String>> body(HttpStatus status, String message) {
        return ResponseEntity.status(status)
                .body(Map.of(ERROR_KEY, message == null ? status.name() : message));
    }
}
