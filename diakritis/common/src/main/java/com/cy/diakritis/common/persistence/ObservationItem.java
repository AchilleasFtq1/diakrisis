package com.cy.diakritis.common.persistence;

import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSortKey;

/**
 * Behavioural observation (e.g. last-seen of a value) used for velocity/recency features.
 * <p>pk = {@code OBS#<acct>}, sk = {@code KIND#<value>}. {@code ttlEpochSec} drives table TTL.
 */
@DynamoDbBean
public class ObservationItem {

    private String pk;
    private String sk;
    private String accountId;
    private String kind;
    private String value;
    private long firstSeenEpochMs;
    private long lastSeenEpochMs;
    private String lastResolvedAccountRef;
    private String sessionId;
    private long ttlEpochSec;

    @DynamoDbPartitionKey
    public String getPk() {
        return pk;
    }

    public void setPk(String pk) {
        this.pk = pk;
    }

    @DynamoDbSortKey
    public String getSk() {
        return sk;
    }

    public void setSk(String sk) {
        this.sk = sk;
    }

    public String getAccountId() {
        return accountId;
    }

    public void setAccountId(String accountId) {
        this.accountId = accountId;
    }

    public String getKind() {
        return kind;
    }

    public void setKind(String kind) {
        this.kind = kind;
    }

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
    }

    public long getFirstSeenEpochMs() {
        return firstSeenEpochMs;
    }

    public void setFirstSeenEpochMs(long firstSeenEpochMs) {
        this.firstSeenEpochMs = firstSeenEpochMs;
    }

    public long getLastSeenEpochMs() {
        return lastSeenEpochMs;
    }

    public void setLastSeenEpochMs(long lastSeenEpochMs) {
        this.lastSeenEpochMs = lastSeenEpochMs;
    }

    public String getLastResolvedAccountRef() {
        return lastResolvedAccountRef;
    }

    public void setLastResolvedAccountRef(String lastResolvedAccountRef) {
        this.lastResolvedAccountRef = lastResolvedAccountRef;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public long getTtlEpochSec() {
        return ttlEpochSec;
    }

    public void setTtlEpochSec(long ttlEpochSec) {
        this.ttlEpochSec = ttlEpochSec;
    }
}
