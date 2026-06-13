package com.cy.diakritis.bank.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Admin payload to assign (replace) a user's primary role via
 * {@code POST /admin/users/{username}/roles}. {@code role} is a Role literal
 * (CUSTOMER/APPROVER/OPS/ADMIN).
 */
public record AssignRoleRequest(
        @NotBlank String role
) {
}
