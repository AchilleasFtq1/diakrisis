package com.cy.diakritis.common.persistence;

import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSortKey;

/**
 * Name-indexed counterparty record supporting Confirmation-of-Payee (T4 dual key).
 * <p>pk = {@code ACC#<acct>}, sk = {@code NAME#<NORMNAME>}.
 */
@DynamoDbBean
public class CounterpartyByNameItem {

    private String pk;
    private String sk;
    private String normalizedName;
    private String displayName;
    private String establishedIban;
    private String establishedCounterpartyKey;
    private long payCount;
    private long meanAmountCents;
    private long firstSeenEpochMs;
    private long lastSeenEpochMs;
    private String source;

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

    public String getNormalizedName() {
        return normalizedName;
    }

    public void setNormalizedName(String normalizedName) {
        this.normalizedName = normalizedName;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getEstablishedIban() {
        return establishedIban;
    }

    public void setEstablishedIban(String establishedIban) {
        this.establishedIban = establishedIban;
    }

    public String getEstablishedCounterpartyKey() {
        return establishedCounterpartyKey;
    }

    public void setEstablishedCounterpartyKey(String establishedCounterpartyKey) {
        this.establishedCounterpartyKey = establishedCounterpartyKey;
    }

    public long getPayCount() {
        return payCount;
    }

    public void setPayCount(long payCount) {
        this.payCount = payCount;
    }

    public long getMeanAmountCents() {
        return meanAmountCents;
    }

    public void setMeanAmountCents(long meanAmountCents) {
        this.meanAmountCents = meanAmountCents;
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

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }
}
