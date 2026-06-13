package com.cy.diakritis.iam.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Credentials posted to {@code /auth/login}.
 */
public record LoginRequest(
        @NotBlank String username,
        @NotBlank String password
) {
}
