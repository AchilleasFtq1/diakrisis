package com.cy.diakritis.engine;

import com.cy.diakritis.engine.store.ReputationView;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * In-memory {@link ReputationView} for engine tests: seed a counterparty's last-flag timestamp and
 * worst outcome to exercise the X1 cross-account-reputation signal.
 */
public final class FakeReputation implements ReputationView {

    private final Map<String, Long> lastFlag = new LinkedHashMap<>();
    private final Map<String, String> worstOutcome = new LinkedHashMap<>();

    public FakeReputation flag(String counterpartyKey, long lastFlagEpochMs, String worst) {
        lastFlag.put(counterpartyKey, lastFlagEpochMs);
        if (worst != null) {
            worstOutcome.put(counterpartyKey, worst);
        }
        return this;
    }

    @Override
    public Optional<Long> lastFlagEpochMs(String counterpartyKey) {
        return Optional.ofNullable(lastFlag.get(counterpartyKey));
    }

    @Override
    public Optional<String> worstOutcome(String counterpartyKey) {
        return Optional.ofNullable(worstOutcome.get(counterpartyKey));
    }
}
