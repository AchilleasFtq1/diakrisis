package com.cy.diakritis.engine.store;

import java.util.Optional;

/**
 * Read-only access to the behavioural observation store: per-account last-seen timestamps of
 * device, IP, session and counterparty values. Feeds the M1 recency features (D1/D4/D10/D15),
 * where "never seen" maps to the model's missing sentinel.
 *
 * <p>Timestamps are epoch-millis. An empty {@link Optional} means the value was never observed.
 */
public interface ObservationsView {

    /** Epoch-millis a {@code (kind, value)} pair for this account was last observed, empty if never. */
    Optional<Long> lastSeenEpochMs(String accountId, String kind, String value);

    /** An {@link ObservationsView} that has never observed anything (cold start / test default). */
    static ObservationsView empty() {
        return (accountId, kind, value) -> Optional.empty();
    }
}
