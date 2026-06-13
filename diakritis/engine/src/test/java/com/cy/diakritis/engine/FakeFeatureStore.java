package com.cy.diakritis.engine;

import com.cy.diakritis.common.persistence.RecentPayment;
import com.cy.diakritis.engine.store.AccountStatsView;
import com.cy.diakritis.engine.store.CounterpartyByNameView;
import com.cy.diakritis.engine.store.FeatureStore;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * In-memory {@link FeatureStore} for engine tests. Lets a test seed exact baselines for a
 * (account, counterparty) pair, account stats and Confirmation-of-Payee records, mirroring the
 * shapes the DynamoDB implementation produces.
 */
public final class FakeFeatureStore implements FeatureStore {

    private record PairKey(String accountId, String cpKey) {
    }

    private final Map<PairKey, Long> priorCount = new HashMap<>();
    private final Map<PairKey, Long> meanCents = new HashMap<>();
    private final Map<PairKey, Long> firstSeen = new HashMap<>();
    private final Map<PairKey, List<RecentPayment>> recent = new HashMap<>();
    private final Map<String, AccountStatsView> stats = new HashMap<>();
    private final Map<String, CounterpartyByNameView> byName = new HashMap<>();

    public FakeFeatureStore seedBaseline(String accountId, String cpKey, long count, long meanAmountCents,
                                  long firstSeenEpochMs, List<RecentPayment> recentPayments) {
        PairKey key = new PairKey(accountId, cpKey);
        priorCount.put(key, count);
        meanCents.put(key, meanAmountCents);
        firstSeen.put(key, firstSeenEpochMs);
        recent.put(key, recentPayments == null ? List.of() : List.copyOf(recentPayments));
        return this;
    }

    public FakeFeatureStore seedStats(String accountId, AccountStatsView view) {
        stats.put(accountId, view);
        return this;
    }

    public FakeFeatureStore seedByName(String accountId, String normalizedName, CounterpartyByNameView view) {
        byName.put(accountId + "::" + normalizedName, view);
        return this;
    }

    @Override
    public long priorPaymentCount(String accountId, String cpKey) {
        return priorCount.getOrDefault(new PairKey(accountId, cpKey), 0L);
    }

    @Override
    public long meanAmountCents(String accountId, String cpKey) {
        return meanCents.getOrDefault(new PairKey(accountId, cpKey), 0L);
    }

    @Override
    public Optional<Long> firstSeenEpochMs(String accountId, String cpKey) {
        return Optional.ofNullable(firstSeen.get(new PairKey(accountId, cpKey)));
    }

    @Override
    public List<RecentPayment> recentPayments(String accountId, String cpKey) {
        return recent.getOrDefault(new PairKey(accountId, cpKey), List.of());
    }

    @Override
    public AccountStatsView accountStats(String accountId) {
        return stats.getOrDefault(accountId,
                new AccountStatsView(0, 0, 0, 0, 0, false, false, List.of()));
    }

    @Override
    public Optional<CounterpartyByNameView> byName(String accountId, String normalizedName) {
        return Optional.ofNullable(byName.get(accountId + "::" + normalizedName));
    }
}
