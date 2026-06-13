package com.cy.diakritis.common.persistence;

import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSortKey;

import java.util.List;

/**
 * A lifecycle case for a held or approval-pending decision.
 * <p>pk = {@code CASE#<id>}, sk = {@code CASE}.
 */
@DynamoDbBean
public class CaseItem {

    private String pk;
    private String sk;
    private String eventId;
    private String state;
    private String initiatorUserId;
    private String approverUserId;
    private long holdExpiryEpochMs;
    private List<String> batchHeldItemIds;
    private long createdEpochMs;

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

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public String getInitiatorUserId() {
        return initiatorUserId;
    }

    public void setInitiatorUserId(String initiatorUserId) {
        this.initiatorUserId = initiatorUserId;
    }

    public String getApproverUserId() {
        return approverUserId;
    }

    public void setApproverUserId(String approverUserId) {
        this.approverUserId = approverUserId;
    }

    public long getHoldExpiryEpochMs() {
        return holdExpiryEpochMs;
    }

    public void setHoldExpiryEpochMs(long holdExpiryEpochMs) {
        this.holdExpiryEpochMs = holdExpiryEpochMs;
    }

    public List<String> getBatchHeldItemIds() {
        return batchHeldItemIds;
    }

    public void setBatchHeldItemIds(List<String> batchHeldItemIds) {
        this.batchHeldItemIds = batchHeldItemIds;
    }

    public long getCreatedEpochMs() {
        return createdEpochMs;
    }

    public void setCreatedEpochMs(long createdEpochMs) {
        this.createdEpochMs = createdEpochMs;
    }
}
