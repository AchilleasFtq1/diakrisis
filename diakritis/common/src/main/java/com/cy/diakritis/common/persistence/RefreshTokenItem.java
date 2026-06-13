package com.cy.diakritis.common.persistence;

import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;

/**
 * A persisted opaque refresh token. Refresh tokens are NOT JWTs: they are high-entropy random ids
 * minted server-side and stored here so they can be revoked and rotated. The raw token id itself is
 * the secret presented by the client; server-side validation is "row exists, not revoked, not
 * expired".
 *
 * <p>pk = {@code RT#<tokenId>}. The denormalized {@code userId}/{@code username}/{@code role}/
 * {@code accountId} let {@code /auth/refresh} mint a fresh access JWT without a second user lookup.
 */
@DynamoDbBean
public class RefreshTokenItem {

    private String pk;
    private String tokenId;
    private String userId;
    private String username;
    private String role;
    private String accountId;
    private long expiresEpochMs;
    private boolean revoked;

    @DynamoDbPartitionKey
    public String getPk() {
        return pk;
    }

    public void setPk(String pk) {
        this.pk = pk;
    }

    public String getTokenId() {
        return tokenId;
    }

    public void setTokenId(String tokenId) {
        this.tokenId = tokenId;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getAccountId() {
        return accountId;
    }

    public void setAccountId(String accountId) {
        this.accountId = accountId;
    }

    public long getExpiresEpochMs() {
        return expiresEpochMs;
    }

    public void setExpiresEpochMs(long expiresEpochMs) {
        this.expiresEpochMs = expiresEpochMs;
    }

    public boolean isRevoked() {
        return revoked;
    }

    public void setRevoked(boolean revoked) {
        this.revoked = revoked;
    }
}
