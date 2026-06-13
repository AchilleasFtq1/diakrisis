package com.cy.diakritis.common.persistence;

import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSortKey;

/**
 * Cross-account reputation of a counterparty key (flag history / worst outcome).
 * <p>pk = {@code CP#<cpKey>}, sk = {@code REP}. {@code ttlEpochSec} drives table TTL.
 */
@DynamoDbBean
public class CounterpartyReputationItem {

    private String pk;
    private String sk;
    private String counterpartyKey;
    private long lastFlagEpochMs;
    private String worstOutcome;
    private long flagCount;
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

    public String getCounterpartyKey() {
        return counterpartyKey;
    }

    public void setCounterpartyKey(String counterpartyKey) {
        this.counterpartyKey = counterpartyKey;
    }

    public long getLastFlagEpochMs() {
        return lastFlagEpochMs;
    }

    public void setLastFlagEpochMs(long lastFlagEpochMs) {
        this.lastFlagEpochMs = lastFlagEpochMs;
    }

    public String getWorstOutcome() {
        return worstOutcome;
    }

    public void setWorstOutcome(String worstOutcome) {
        this.worstOutcome = worstOutcome;
    }

    public long getFlagCount() {
        return flagCount;
    }

    public void setFlagCount(long flagCount) {
        this.flagCount = flagCount;
    }

    public long getTtlEpochSec() {
        return ttlEpochSec;
    }

    public void setTtlEpochSec(long ttlEpochSec) {
        this.ttlEpochSec = ttlEpochSec;
    }
}
