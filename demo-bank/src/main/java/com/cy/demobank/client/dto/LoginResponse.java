package com.cy.demobank.client.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

/**
 * The IAM login response (snake_case wire):
 * {@code {token, refresh_token, sub, roles, account_id, expires_at}}. {@code token} is the
 * short-lived access JWT demo-bank forwards as the Bearer to the decision-service.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LoginResponse(
        String token,
        String refreshToken,
        String sub,
        List<String> roles,
        String accountId,
        Instant expiresAt
) {
}
