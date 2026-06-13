package com.cy.diakritis.bank.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Admin payload to create a user via {@code POST /admin/users}. {@code role} is optional (defaults to
 * CUSTOMER); {@code accountId} binds a CUSTOMER to an account.
 */
public record AdminCreateUserRequest(
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
