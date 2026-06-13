package com.cy.diakritis.common.persistence;

import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSortKey;

import java.util.List;

/**
 * Per-account outgoing-payment statistics used as robust-z baselines.
 * <p>pk = {@code ACC#<acct>}, sk = {@code META}.
 */
@DynamoDbBean
public class AccountStatsItem {

    private String pk;
    private String sk;
    private long outMeanAmountCents;
    private long outStdAmountCents;
    private long outMedianAmountCents;
    private long outMadAmountCents;
    private long outTxnCount;
    private boolean isBusinessAccount;
    private boolean hasDesignatedApprover;
    private List<String> approverUserIds;
    private boolean isVulnerable;
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

    public long getOutMeanAmountCents() {
        return outMeanAmountCents;
    }

    public void setOutMeanAmountCents(long outMeanAmountCents) {
        this.outMeanAmountCents = outMeanAmountCents;
    }

    public long getOutStdAmountCents() {
        return outStdAmountCents;
    }

    public void setOutStdAmountCents(long outStdAmountCents) {
        this.outStdAmountCents = outStdAmountCents;
    }

    public long getOutMedianAmountCents() {
        return outMedianAmountCents;
    }

    public void setOutMedianAmountCents(long outMedianAmountCents) {
        this.outMedianAmountCents = outMedianAmountCents;
    }

    public long getOutMadAmountCents() {
        return outMadAmountCents;
    }

    public void setOutMadAmountCents(long outMadAmountCents) {
        this.outMadAmountCents = outMadAmountCents;
    }

    public long getOutTxnCount() {
        return outTxnCount;
    }

    public void setOutTxnCount(long outTxnCount) {
        this.outTxnCount = outTxnCount;
    }

    public boolean isBusinessAccount() {
        return isBusinessAccount;
    }

    public void setBusinessAccount(boolean businessAccount) {
        isBusinessAccount = businessAccount;
    }

    public boolean isHasDesignatedApprover() {
        return hasDesignatedApprover;
    }

    public void setHasDesignatedApprover(boolean hasDesignatedApprover) {
        this.hasDesignatedApprover = hasDesignatedApprover;
    }

    public List<String> getApproverUserIds() {
        return approverUserIds;
    }

    public void setApproverUserIds(List<String> approverUserIds) {
        this.approverUserIds = approverUserIds;
    }

    public boolean isVulnerable() {
        return isVulnerable;
    }

    public void setVulnerable(boolean vulnerable) {
        isVulnerable = vulnerable;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }
}
