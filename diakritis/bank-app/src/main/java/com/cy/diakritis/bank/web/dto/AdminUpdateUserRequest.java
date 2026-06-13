package com.cy.diakritis.bank.web.dto;

/**
 * Admin payload to update a user via {@code PUT /admin/users/{username}}. Every field is optional; a
 * null field leaves the corresponding attribute unchanged (partial update). {@code role} is a Role
 * literal (CUSTOMER/APPROVER/OPS/ADMIN); {@code enabled} toggles the account; {@code accountId}
 * rebinds the account.
 */
public record AdminUpdateUserRequest(
        String role,
        Boolean enabled,
        String accountId
) {
}
