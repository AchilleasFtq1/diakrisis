package com.cy.diakritis.common.persistence;

import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSortKey;

/**
 * The labelled result of a decision's lifecycle — the persisted training signal of the SDD §9.5
 * {@code decisions → outcomes → calibration} loop. One row is written per event the moment its
 * lifecycle reaches a terminal, judgeable state (a HELD action cancelled or released, or a
 * four-eyes action approved or rejected).
 *
 * <p>pk = {@code OUTCOME#<eventId>}, sk = {@code OUTCOME} (one outcome per event). The firing
 * {@code signalPattern} — the comma-joined ids of the signals that actually contributed to the
 * original verdict — is recorded alongside each outcome so the loop is concrete: a future
 * calibration pass can read which signal combinations led to true catches versus false positives.
 */
@DynamoDbBean
public class OutcomeItem {

    private String pk;
    private String sk;
    private String eventId;
    private String accountId;
    private String outcome;
    private String signalPattern;
    private long amountCents;
    private long epochMs;

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

    public String getOutcome() {
        return outcome;
    }

    public void setOutcome(String outcome) {
        this.outcome = outcome;
    }

    public String getSignalPattern() {
        return signalPattern;
    }

    public void setSignalPattern(String signalPattern) {
        this.signalPattern = signalPattern;
    }

    /** Action amount in euro-cents — the money this outcome's hold saved (CONFIRMED_SAVE) or released. */
    public long getAmountCents() {
        return amountCents;
    }

    public void setAmountCents(long amountCents) {
        this.amountCents = amountCents;
    }

    public long getEpochMs() {
        return epochMs;
    }

    public void setEpochMs(long epochMs) {
        this.epochMs = epochMs;
    }
}
