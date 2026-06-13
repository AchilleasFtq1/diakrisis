package com.cy.diakritis.bank.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Self-service registration payload posted to {@code /auth/register}.
 *
 * <p>{@code role} is optional and defaults to {@code CUSTOMER}; {@code accountId} binds a CUSTOMER to
 * an account and is ignored for non-account roles. Password length is enforced here (controller-layer
 * validation) so a too-short password is rejected with 400 before any persistence.
 */
public record RegisterRequest(
        @NotBlank
        @Size(min = 3, max = 64, message = "username must be 3-64 characters")
        String username,

        @NotBlank
        @Size(min = 4, max = 128, message = "password must be 4-128 characters")
        String password,

        String role,

        String accountId
) {
}
