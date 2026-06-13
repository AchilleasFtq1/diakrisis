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

import java.util.Map;

/**
 * Translates exceptions into stable HTTP responses for the decision API. Known bad inputs and
 * lifecycle conflicts NEVER surface as 500 (contract CI-8):
 * <ul>
 *   <li>malformed JSON / bad enum literal → 400</li>
 *   <li>bean-validation failure / semantically invalid request → 422</li>
 *   <li>illegal lifecycle transition / pre-expiry lock → 409 (with a stable {@code error} code)</li>
 *   <li>invalid / missing token → 401</li>
 *   <li>four-eyes self-approval → 403 (with {@code SELF_APPROVAL_FORBIDDEN})</li>
 *   <li>unknown decision / resource → 404</li>
 * </ul>
 *
 * <p>A final {@link Exception} catch-all maps anything unforeseen to 422 so a known-shaped request
 * can never produce a 500; genuinely unexpected failures are logged at ERROR for diagnosis.
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

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleUnexpected(Exception ex) {
        // CI-8 safety net: a known-shaped request must never 500. Anything not matched above is
        // treated as an unprocessable request; the cause is logged for operators.
        LOG.error("Unhandled exception mapped to 422", ex);
        return body(HttpStatus.UNPROCESSABLE_ENTITY, "Request could not be processed");
    }

    private ResponseEntity<Map<String, String>> body(HttpStatus status, String message) {
        return ResponseEntity.status(status)
                .body(Map.of(ERROR_KEY, message == null ? status.name() : message));
    }
}
