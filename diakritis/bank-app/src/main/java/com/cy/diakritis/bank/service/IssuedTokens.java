package com.cy.diakritis.bank.service;

import com.cy.diakritis.common.security.Role;

import java.time.Instant;

/**
 * A freshly issued token pair plus the resolved identity context, returned by
 * {@link UserService#issueTokens} / {@link UserService#authenticate} / {@link UserService#refresh}.
 *
 * @param accessToken  short-lived signed access JWT (sub/role/accountId)
 * @param refreshToken opaque, revocable refresh token id (NOT a JWT)
 * @param sub          the subject (username) the tokens were minted for
 * @param role         the primary role encoded in the access token
 * @param accountId    the bound account id, null for non-account roles
 * @param expiresAt    the access token's expiry instant
 */
public record IssuedTokens(
        String accessToken,
        String refreshToken,
        String sub,
        Role role,
        String accountId,
        Instant expiresAt
) {
}
