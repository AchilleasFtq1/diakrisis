package com.cy.diakritis.iam.web.dto;

/**
 * The refresh token to revoke on {@code /auth/logout}. Optional in the body: a caller may instead
 * present the refresh token via the {@code Authorization: Bearer <refreshToken>} header. Logout is
 * idempotent, so this is not {@code @NotBlank}.
 */
public record LogoutRequest(
        String refreshToken
) {
}
