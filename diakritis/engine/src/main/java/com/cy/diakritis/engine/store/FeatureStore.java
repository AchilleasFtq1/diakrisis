package com.cy.diakritis.engine.store;

import com.cy.diakritis.common.persistence.RecentPayment;

import java.util.List;
import java.util.Optional;

/**
 * Read-only access to the offline-computed feature baselines the engine reads on the hot path.
 *
 * <p>The engine declares this interface; the concrete implementation lives in decision-service
 * over DynamoDB. All money is integer euro-cents and all timestamps are epoch-millis so the
 * scoring path never carries {@code BigDecimal} or {@code Instant} arithmetic.
 *
 * <p>Identity rule (counterparty key): {@code resolvedAccountRef} if present, otherwise
 * {@code addressing + "|" + value}.
 */
public interface FeatureStore {

    /** Number of prior outgoing payments from {@code accountId} to {@code cpKey}; 0 if none. */
    long priorPaymentCount(String accountId, String cpKey);

    /** Mean amount (cents) historically paid from {@code accountId} to {@code cpKey}; 0 if none. */
    long meanAmountCents(String accountId, String cpKey);

    /** Epoch-millis the pair {@code (accountId, cpKey)} was first seen, empty if never. */
    Optional<Long> firstSeenEpochMs(String accountId, String cpKey);

    /** Most recent outgoing payments to {@code cpKey}, oldest-to-newest; empty list if none. */
    List<RecentPayment> recentPayments(String accountId, String cpKey);

    /** Account-level outgoing statistics and business/approver flags. */
    AccountStatsView accountStats(String accountId);

    /** Name-indexed counterparty record for Confirmation-of-Payee, empty if the name is unknown. */
    Optional<CounterpartyByNameView> byName(String accountId, String normalizedName);
}
