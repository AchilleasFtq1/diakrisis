package com.cy.diakritis.iam.web.dto;

import com.cy.diakritis.common.persistence.UserItem;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Admin-safe projection of a {@link UserItem}. NEVER carries the password hash. Wire shape
 * (snake_case): {@code {user_id, username, roles, account_id, enabled, created_epoch_ms}}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record UserView(
        String userId,
        String username,
        List<String> roles,
        String accountId,
        boolean enabled,
        long createdEpochMs
) {

    public static UserView from(UserItem user) {
        return new UserView(
                user.getUserId(),
                user.getUsername(),
                user.getRoles() == null ? List.of() : List.copyOf(user.getRoles()),
                user.getAccountId(),
                user.isEnabled(),
                user.getCreatedEpochMs());
    }

}
