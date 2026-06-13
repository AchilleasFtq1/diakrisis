package com.cy.diakritis.iam.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Admin payload to reset a user's password via {@code POST /admin/users/{username}/password}. The new
 * password is BCrypt-hashed; the plaintext is never stored or logged.
 */
public record ResetPasswordRequest(
        @NotBlank
        @Size(min = 4, max = 128, message = "password must be 4-128 characters")
        String password
) {
}
