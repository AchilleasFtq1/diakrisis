package com.cy.diakritis.common.persistence;

import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSortKey;

import java.util.List;

/**
 * Rolling risk posture for an account (recent liquidation/limit/beneficiary activity).
 * <p>pk = {@code ACC#<acct>}, sk = {@code POSTURE}. {@code ttlEpochSec} drives table TTL.
 */
@DynamoDbBean
public class AccountPostureItem {

    private String pk;
    private String sk;
    private long fundsFreedEur72hCents;
    private long limitRaised72hCents;
    private long beneficiaryAddCount72h;
    private long lastDepositBreakEpochMs;
    // Per-counter last-activity timestamps so each kill-chain counter can decay over its OWN window
    // (K1 over the 168h funds-freed horizon via lastDepositBreakEpochMs, K2 over its limit-raise
    // window, K3 over its beneficiary-add window) instead of all three being gated on the deposit break.
    private long lastLimitRaiseEpochMs;
    private long lastBeneficiaryAddEpochMs;
    // Bounded ring of the most recent eventIds whose contribution has already been applied to the
    // counters above. Makes the posture INCREMENT idempotent per eventId so it can be committed before
    // the decision-row putIfAbsent without a concurrent duplicate (or a crash-replay re-commit)
    // double-counting a counter. Trimmed to a fixed cap by the writer.
    private List<String> appliedEventIds;
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

    public long getFundsFreedEur72hCents() {
        return fundsFreedEur72hCents;
    }

    public void setFundsFreedEur72hCents(long fundsFreedEur72hCents) {
        this.fundsFreedEur72hCents = fundsFreedEur72hCents;
    }

    public long getLimitRaised72hCents() {
        return limitRaised72hCents;
    }

    public void setLimitRaised72hCents(long limitRaised72hCents) {
        this.limitRaised72hCents = limitRaised72hCents;
    }

    public long getBeneficiaryAddCount72h() {
        return beneficiaryAddCount72h;
    }

    public void setBeneficiaryAddCount72h(long beneficiaryAddCount72h) {
        this.beneficiaryAddCount72h = beneficiaryAddCount72h;
    }

    public long getLastDepositBreakEpochMs() {
        return lastDepositBreakEpochMs;
    }

    public void setLastDepositBreakEpochMs(long lastDepositBreakEpochMs) {
        this.lastDepositBreakEpochMs = lastDepositBreakEpochMs;
    }

    public long getLastLimitRaiseEpochMs() {
        return lastLimitRaiseEpochMs;
    }

    public void setLastLimitRaiseEpochMs(long lastLimitRaiseEpochMs) {
        this.lastLimitRaiseEpochMs = lastLimitRaiseEpochMs;
    }

    public long getLastBeneficiaryAddEpochMs() {
        return lastBeneficiaryAddEpochMs;
    }

    public void setLastBeneficiaryAddEpochMs(long lastBeneficiaryAddEpochMs) {
        this.lastBeneficiaryAddEpochMs = lastBeneficiaryAddEpochMs;
    }

    public List<String> getAppliedEventIds() {
        return appliedEventIds;
    }

    public void setAppliedEventIds(List<String> appliedEventIds) {
        this.appliedEventIds = appliedEventIds;
    }

    public long getTtlEpochSec() {
        return ttlEpochSec;
    }

    public void setTtlEpochSec(long ttlEpochSec) {
        this.ttlEpochSec = ttlEpochSec;
    }
}
