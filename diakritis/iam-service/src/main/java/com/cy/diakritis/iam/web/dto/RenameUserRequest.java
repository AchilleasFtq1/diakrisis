package com.cy.diakritis.iam.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Admin payload to rename a user via {@code POST /admin/users/{username}/username}. Username is the
 * identity key, so a rename re-keys the record (new username must be free) and invalidates the user's
 * existing refresh tokens. Wire field: {@code new_username}.
 */
public record RenameUserRequest(
        @NotBlank
        @Size(min = 3, max = 64, message = "username must be 3-64 characters")
        String newUsername
) {
}
