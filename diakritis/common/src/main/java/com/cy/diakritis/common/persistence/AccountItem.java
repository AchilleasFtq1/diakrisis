package com.cy.diakritis.common.persistence;

import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSortKey;

import java.util.List;

/**
 * Bank-side account facts owned by bank-app (balance, approvers, term deposits).
 * <p>pk = {@code ACC#<acct>}, sk = {@code META}.
 */
@DynamoDbBean
public class AccountItem {

    private String pk;
    private String sk;
    private String displayName;
    private long availableBalanceCents;
    private String ownerUserId;
    private boolean isBusiness;
    private List<String> approverUserIds;
    private List<TermDeposit> termDeposits;
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

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public long getAvailableBalanceCents() {
        return availableBalanceCents;
    }

    public void setAvailableBalanceCents(long availableBalanceCents) {
        this.availableBalanceCents = availableBalanceCents;
    }

    public String getOwnerUserId() {
        return ownerUserId;
    }

    public void setOwnerUserId(String ownerUserId) {
        this.ownerUserId = ownerUserId;
    }

    public boolean isBusiness() {
        return isBusiness;
    }

    public void setBusiness(boolean business) {
        isBusiness = business;
    }

    public List<String> getApproverUserIds() {
        return approverUserIds;
    }

    public void setApproverUserIds(List<String> approverUserIds) {
        this.approverUserIds = approverUserIds;
    }

    public List<TermDeposit> getTermDeposits() {
        return termDeposits;
    }

    public void setTermDeposits(List<TermDeposit> termDeposits) {
        this.termDeposits = termDeposits;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }
}
