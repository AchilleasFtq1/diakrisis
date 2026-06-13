package com.cy.diakritis.decision.store;

import com.cy.diakritis.common.persistence.CounterpartyReputationItem;
import com.cy.diakritis.decision.repo.CounterpartyReputationRepository;
import com.cy.diakritis.engine.store.ReputationView;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * DynamoDB-backed {@link ReputationView} over the {@code CounterpartyReputation} table — the read
 * side of the §4B cross-account moat. X1 calls this on every incoming transfer to every account, so
 * a counterparty flagged (HELD / BLOCKED) on one account is recognised on the next account's payment
 * to the same resolved identity, with the X1 half-life decay applied in the signal.
 *
 * <p>The lookup is total: a counterparty with no flag on record returns an empty {@link Optional}
 * and X1 degrades cleanly to 0.
 */
@Component
public class DynamoReputationView implements ReputationView {

    private final CounterpartyReputationRepository counterpartyReputationRepository;

    public DynamoReputationView(CounterpartyReputationRepository counterpartyReputationRepository) {
        this.counterpartyReputationRepository = counterpartyReputationRepository;
    }

    @Override
    public Optional<Long> lastFlagEpochMs(String counterpartyKey) {
        return counterpartyReputationRepository.find(counterpartyKey)
                .map(CounterpartyReputationItem::getLastFlagEpochMs)
                .filter(epoch -> epoch > 0);
    }

    @Override
    public Optional<String> worstOutcome(String counterpartyKey) {
        return counterpartyReputationRepository.find(counterpartyKey)
                .map(CounterpartyReputationItem::getWorstOutcome)
                .filter(outcome -> outcome != null && !outcome.isBlank());
    }
}
