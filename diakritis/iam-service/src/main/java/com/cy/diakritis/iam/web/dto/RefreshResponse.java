package com.cy.diakritis.iam.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * Result of a successful refresh. A new access JWT plus a freshly rotated refresh token (the
 * presented refresh token is revoked). Wire shape (snake_case):
 * {@code {token, refresh_token, expires_at}}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RefreshResponse(
        String token,
        String refreshToken,
        Instant expiresAt
) {
}
