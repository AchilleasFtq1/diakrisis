package com.cy.diakritis.decision.store;

import com.cy.diakritis.common.persistence.AccountStatsItem;
import com.cy.diakritis.common.persistence.CounterpartyBaselineItem;
import com.cy.diakritis.common.persistence.CounterpartyByNameItem;
import com.cy.diakritis.common.persistence.RecentPayment;
import com.cy.diakritis.decision.repo.FeatureRepository;
import com.cy.diakritis.engine.store.AccountStatsView;
import com.cy.diakritis.engine.store.CounterpartyByNameView;
import com.cy.diakritis.engine.store.FeatureStore;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * DynamoDB-backed implementation of the engine's read-only {@link FeatureStore}, reading the
 * offline-computed feature tables ({@code CounterpartyBaseline}, {@code AccountStats},
 * {@code CounterpartyByName}). All money is integer euro-cents and all timestamps epoch-millis, so
 * the engine's hot path carries no {@code BigDecimal} or {@code Instant} arithmetic.
 *
 * <p>Cold-start defensiveness: any pair with no baseline returns the documented "no history"
 * defaults (count 0, mean 0, empty first-seen, empty recent list) rather than throwing, so the
 * engine's novelty signals fire correctly on a first-ever payment.
 */
@Component
public class DynamoFeatureStore implements FeatureStore {

    private final FeatureRepository featureRepository;

    public DynamoFeatureStore(FeatureRepository featureRepository) {
        this.featureRepository = featureRepository;
    }

    @Override
    public long priorPaymentCount(String accountId, String cpKey) {
        return featureRepository.baseline(accountId, cpKey)
                .map(CounterpartyBaselineItem::getPayCount)
                .orElse(0L);
    }

    @Override
    public long meanAmountCents(String accountId, String cpKey) {
        return featureRepository.baseline(accountId, cpKey)
                .map(CounterpartyBaselineItem::getMeanAmountCents)
                .orElse(0L);
    }

    @Override
    public Optional<Long> firstSeenEpochMs(String accountId, String cpKey) {
        return featureRepository.baseline(accountId, cpKey)
                .map(CounterpartyBaselineItem::getFirstSeenEpochMs);
    }

    @Override
    public List<RecentPayment> recentPayments(String accountId, String cpKey) {
        return featureRepository.baseline(accountId, cpKey)
                .map(CounterpartyBaselineItem::getRecentPayments)
                .map(list -> list == null ? List.<RecentPayment>of() : List.copyOf(list))
                .orElse(List.of());
    }

    @Override
    public AccountStatsView accountStats(String accountId) {
        Optional<AccountStatsItem> stats = featureRepository.accountStats(accountId);
        if (stats.isEmpty()) {
            return null;
        }
        AccountStatsItem item = stats.get();
        return new AccountStatsView(
                item.getOutMeanAmountCents(),
                item.getOutStdAmountCents(),
                item.getOutMedianAmountCents(),
                item.getOutMadAmountCents(),
                item.getOutTxnCount(),
                item.isBusinessAccount(),
                item.isHasDesignatedApprover(),
                item.getApproverUserIds());
    }

    @Override
    public Optional<CounterpartyByNameView> byName(String accountId, String normalizedName) {
        return featureRepository.byName(accountId, normalizedName)
                .map(DynamoFeatureStore::toView);
    }

    private static CounterpartyByNameView toView(CounterpartyByNameItem item) {
        return new CounterpartyByNameView(
                item.getNormalizedName(),
                item.getDisplayName(),
                item.getEstablishedIban(),
                item.getEstablishedCounterpartyKey(),
                item.getPayCount(),
                item.getMeanAmountCents(),
                item.getFirstSeenEpochMs(),
                item.getLastSeenEpochMs());
    }
}
