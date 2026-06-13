package com.cy.diakritis.common.persistence;

import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;

/**
 * A term deposit held on an account, stored as a nested attribute on {@link AccountItem}.
 * Money is integer euro-cents; maturity is epoch-millis.
 */
@DynamoDbBean
public class TermDeposit {

    private String depositId;
    private long principalCents;
    private long maturityEpochMs;
    private long penaltyCents;
    private boolean broken;

    public TermDeposit() {
    }

    public TermDeposit(String depositId, long principalCents, long maturityEpochMs, long penaltyCents, boolean broken) {
        this.depositId = depositId;
        this.principalCents = principalCents;
        this.maturityEpochMs = maturityEpochMs;
        this.penaltyCents = penaltyCents;
        this.broken = broken;
    }

    public String getDepositId() {
        return depositId;
    }

    public void setDepositId(String depositId) {
        this.depositId = depositId;
    }

    public long getPrincipalCents() {
        return principalCents;
    }

    public void setPrincipalCents(long principalCents) {
        this.principalCents = principalCents;
    }

    public long getMaturityEpochMs() {
        return maturityEpochMs;
    }

    public void setMaturityEpochMs(long maturityEpochMs) {
        this.maturityEpochMs = maturityEpochMs;
    }

    public long getPenaltyCents() {
        return penaltyCents;
    }

    public void setPenaltyCents(long penaltyCents) {
        this.penaltyCents = penaltyCents;
    }

    public boolean isBroken() {
        return broken;
    }

    public void setBroken(boolean broken) {
        this.broken = broken;
    }
}
