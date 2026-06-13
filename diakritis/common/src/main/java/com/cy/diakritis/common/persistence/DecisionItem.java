package com.cy.diakritis.common.persistence;

import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSortKey;

/**
 * Persisted decision record for idempotent replay.
 * <p>pk = {@code EVENT#<id>}, sk = {@code DECISION}. {@code responseJson} is the verbatim
 * serialized {@code Decision} body so replays return byte-identical output.
 */
@DynamoDbBean
public class DecisionItem {

    private String pk;
    private String sk;
    private String eventId;
    private String accountId;
    private String initiatorSub;
    private long createdEpochMs;
    private String responseJson;
    private String lifecycleState;
    private long holdExpiresEpochMs;

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

    public String getEventId() {
        return eventId;
    }

    public void setEventId(String eventId) {
        this.eventId = eventId;
    }

    public String getAccountId() {
        return accountId;
    }

    public void setAccountId(String accountId) {
        this.accountId = accountId;
    }

    public String getInitiatorSub() {
        return initiatorSub;
    }

    public void setInitiatorSub(String initiatorSub) {
        this.initiatorSub = initiatorSub;
    }

    public long getCreatedEpochMs() {
        return createdEpochMs;
    }

    public void setCreatedEpochMs(long createdEpochMs) {
        this.createdEpochMs = createdEpochMs;
    }

    public String getResponseJson() {
        return responseJson;
    }

    public void setResponseJson(String responseJson) {
        this.responseJson = responseJson;
    }

    public String getLifecycleState() {
        return lifecycleState;
    }

    public void setLifecycleState(String lifecycleState) {
        this.lifecycleState = lifecycleState;
    }

    public long getHoldExpiresEpochMs() {
        return holdExpiresEpochMs;
    }

    public void setHoldExpiresEpochMs(long holdExpiresEpochMs) {
        this.holdExpiresEpochMs = holdExpiresEpochMs;
    }
}
