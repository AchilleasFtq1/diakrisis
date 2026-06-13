package com.cy.diakritis.common.persistence;

import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;

/**
 * A single recent outgoing payment to a counterparty, stored as a nested attribute on
 * {@link CounterpartyBaselineItem}. Money is integer euro-cents; time is epoch-millis.
 */
@DynamoDbBean
public class RecentPayment {

    private long amountCents;
    private long epochMs;

    public RecentPayment() {
    }

    public RecentPayment(long amountCents, long epochMs) {
        this.amountCents = amountCents;
        this.epochMs = epochMs;
    }

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
