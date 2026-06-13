package com.cy.diakritis.engine.store;

import java.util.List;

/**
 * Read-only projection of an account's outgoing-payment statistics, used as the robust-z
 * baseline (median/MAD) and to drive business/approver policy routing.
 *
 * <p>All monetary fields are integer euro-cents. Implementations live in the persistence
 * tier (decision-service over DynamoDB); the engine only consumes this view.
 *
 * <p>{@code isVulnerable} carries the §17 vulnerability flag the engine reads after banding to
 * apply the one-band friction escalation for flagged-vulnerable accounts.
 */
public record AccountStatsView(
        long outMeanAmountCents,
        long outStdAmountCents,
        long outMedianAmountCents,
        long outMadAmountCents,
        long outTxnCount,
        boolean isBusinessAccount,
        boolean hasDesignatedApprover,
        List<String> approverUserIds,
        boolean isVulnerable
) {
    public AccountStatsView {
        approverUserIds = approverUserIds == null ? List.of() : List.copyOf(approverUserIds);
    }

    /**
     * Backward-compatible factory for a non-vulnerable account. Defaults {@code isVulnerable} to
     * {@code false} so the §17 friction escalation is opt-in: an account is escalated only when it
     * is explicitly flagged vulnerable.
     */
    public AccountStatsView(long outMeanAmountCents,
                            long outStdAmountCents,
                            long outMedianAmountCents,
                            long outMadAmountCents,
                            long outTxnCount,
                            boolean isBusinessAccount,
                            boolean hasDesignatedApprover,
                            List<String> approverUserIds) {
        this(outMeanAmountCents, outStdAmountCents, outMedianAmountCents, outMadAmountCents,
                outTxnCount, isBusinessAccount, hasDesignatedApprover, approverUserIds, false);
    }
}
