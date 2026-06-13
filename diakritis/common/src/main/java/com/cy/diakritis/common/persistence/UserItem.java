package com.cy.diakritis.common.persistence;

import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;

import java.util.List;

/**
 * A persisted application user (the real identity record backing {@code /auth/*}).
 *
 * <p>pk = {@code USER#<username>} (one item per username; usernames are the unique login handle).
 * The password is stored only as a BCrypt hash; the plaintext is never persisted. {@code roles} is a
 * list of {@link com.cy.diakritis.common.security.Role} names; {@code accountId} binds a CUSTOMER to
 * a single account ({@code acc-A}/…) and is null for non-account roles (APPROVER/OPS).
 */
@DynamoDbBean
public class UserItem {

    private String pk;
    private String userId;
    private String username;
    private String passwordHash;
    private List<String> roles;
    private String accountId;
    private boolean enabled;
    private long createdEpochMs;

    @DynamoDbPartitionKey
    public String getPk() {
        return pk;
    }

    public void setPk(String pk) {
        this.pk = pk;
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

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public List<String> getRoles() {
        return roles;
    }

    public void setRoles(List<String> roles) {
        this.roles = roles;
    }

    public String getAccountId() {
        return accountId;
    }

    public void setAccountId(String accountId) {
        this.accountId = accountId;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public long getCreatedEpochMs() {
        return createdEpochMs;
    }

    public void setCreatedEpochMs(long createdEpochMs) {
        this.createdEpochMs = createdEpochMs;
    }
}
