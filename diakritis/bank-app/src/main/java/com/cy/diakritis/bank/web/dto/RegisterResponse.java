package com.cy.diakritis.bank.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Result of a successful registration. Wire shape (snake_case): {@code {user_id, username, role}}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RegisterResponse(
        String userId,
        String username,
        String role
) {
}
