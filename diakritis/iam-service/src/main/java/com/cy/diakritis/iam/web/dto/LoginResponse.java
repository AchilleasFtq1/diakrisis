package com.cy.diakritis.iam.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

/**
 * Issued tokens plus the subject, granted roles and bound account. Wire shape (snake_case):
 * {@code {token, refresh_token, sub, roles, account_id, expires_at}}.
 *
 * <p>{@code token} is the short-lived access JWT; {@code refreshToken} is the opaque, revocable
 * refresh token presented to {@code /auth/refresh}. {@code accountId} is null for non-account roles.
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
