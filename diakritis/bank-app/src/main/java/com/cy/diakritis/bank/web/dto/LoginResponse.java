package com.cy.diakritis.bank.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

/**
 * Issued token plus the subject and granted roles. Shape: {@code {token, sub, roles, expiresAt}}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LoginResponse(
        String token,
        String sub,
        List<String> roles,
        Instant expiresAt
) {
}
