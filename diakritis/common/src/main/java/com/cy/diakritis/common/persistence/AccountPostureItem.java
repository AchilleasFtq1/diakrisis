package com.cy.diakritis.common.persistence;

import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSortKey;

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

    public long getTtlEpochSec() {
        return ttlEpochSec;
    }

    public void setTtlEpochSec(long ttlEpochSec) {
        this.ttlEpochSec = ttlEpochSec;
    }
}
