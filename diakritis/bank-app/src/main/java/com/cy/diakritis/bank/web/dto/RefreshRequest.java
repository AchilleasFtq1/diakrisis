package com.cy.diakritis.bank.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * The opaque refresh token presented to {@code /auth/refresh} to mint a new access token.
 */
public record RefreshRequest(
        @NotBlank String refreshToken
) {
}
