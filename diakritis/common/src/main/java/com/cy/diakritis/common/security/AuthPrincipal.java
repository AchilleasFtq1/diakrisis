package com.cy.diakritis.common.security;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record AuthPrincipal(
        String userId,
        Role role,
        String accountId
) {
}
