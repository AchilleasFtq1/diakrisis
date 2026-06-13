package com.cy.diakritis.common.persistence;

import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSortKey;

import java.util.List;

/**
 * Per-(account, counterparty) payment baseline.
 * <p>pk = {@code ACC#<acct>}, sk = {@code CP#<cpKey>}.
 */
@DynamoDbBean
public class CounterpartyBaselineItem {

    private String pk;
    private String sk;
    private String accountId;
    private String counterpartyKey;
    private String counterpartyIban;
    private String resolvedName;
    private String expectedCopName;
    private long payCount;
    private long meanAmountCents;
    private long stdAmountCents;
    private long firstSeenEpochMs;
    private long lastSeenEpochMs;
    private List<RecentPayment> recentPayments;
    private boolean isStandingOrder;
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

    public String getAccountId() {
        return accountId;
    }

    public void setAccountId(String accountId) {
        this.accountId = accountId;
    }

    public String getCounterpartyKey() {
        return counterpartyKey;
    }

    public void setCounterpartyKey(String counterpartyKey) {
        this.counterpartyKey = counterpartyKey;
    }

    public String getCounterpartyIban() {
        return counterpartyIban;
    }

    public void setCounterpartyIban(String counterpartyIban) {
        this.counterpartyIban = counterpartyIban;
    }

    public String getResolvedName() {
        return resolvedName;
    }

    public void setResolvedName(String resolvedName) {
        this.resolvedName = resolvedName;
    }

    public String getExpectedCopName() {
        return expectedCopName;
    }

    public void setExpectedCopName(String expectedCopName) {
        this.expectedCopName = expectedCopName;
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

    public long getStdAmountCents() {
        return stdAmountCents;
    }

    public void setStdAmountCents(long stdAmountCents) {
        this.stdAmountCents = stdAmountCents;
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

    public List<RecentPayment> getRecentPayments() {
        return recentPayments;
    }

    public void setRecentPayments(List<RecentPayment> recentPayments) {
        this.recentPayments = recentPayments;
    }

    public boolean isStandingOrder() {
        return isStandingOrder;
    }

    public void setStandingOrder(boolean standingOrder) {
        isStandingOrder = standingOrder;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }
}
