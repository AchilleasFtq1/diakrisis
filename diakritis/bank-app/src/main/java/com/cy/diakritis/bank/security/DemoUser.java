package com.cy.diakritis.bank.security;

import com.cy.diakritis.common.security.Role;

/**
 * A seeded demo login. {@code accountId} is null for non-account roles (OPS/APPROVER).
 */
public record DemoUser(String username, String password, Role role, String accountId) {
}
