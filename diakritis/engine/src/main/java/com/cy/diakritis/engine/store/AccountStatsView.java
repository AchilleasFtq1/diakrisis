package com.cy.diakritis.engine.store;

import java.util.List;

/**
 * Read-only projection of an account's outgoing-payment statistics, used as the robust-z
 * baseline (median/MAD) and to drive business/approver policy routing.
 *
 * <p>All monetary fields are integer euro-cents. Implementations live in the persistence
 * tier (decision-service over DynamoDB); the engine only consumes this view.
 */
public record AccountStatsView(
        long outMeanAmountCents,
        long outStdAmountCents,
        long outMedianAmountCents,
        long outMadAmountCents,
        long outTxnCount,
        boolean isBusinessAccount,
        boolean hasDesignatedApprover,
        List<String> approverUserIds
) {
    public AccountStatsView {
        approverUserIds = approverUserIds == null ? List.of() : List.copyOf(approverUserIds);
    }
}
