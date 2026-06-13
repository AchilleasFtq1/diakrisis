package com.cy.diakritis.engine.store;

import java.util.Optional;

/**
 * Read-only access to the engine-owned, cross-account counterparty-reputation store (§4B — the
 * network moat). When any account's transfer to a counterparty is HELD/BLOCKED (or a hold is
 * cancelled = confirmed scam), that resolved counterparty identity is flagged with a timestamp and a
 * worst-outcome label. The X1 signal reads this on every incoming transfer to any account, so the
 * third victim is warned before they finish typing.
 *
 * <p>Timestamps are epoch-millis. An empty {@link Optional} means the counterparty has no flag on
 * record. The store is keyed by the resolved counterparty key (same identity rule as the rest of the
 * engine), so reputation is shared across senders.
 */
public interface ReputationView {

    /** Epoch-millis the counterparty was most recently flagged on any account, empty if never. */
    Optional<Long> lastFlagEpochMs(String counterpartyKey);

    /**
     * The worst outcome the counterparty has triggered on any account (e.g. {@code "BLOCK"},
     * {@code "HOLD"}, {@code "REQUIRE_APPROVAL"}), empty if never flagged. The verdict name lets X1
     * weight a confirmed-block destination above a merely-held one.
     */
    default Optional<String> worstOutcome(String counterpartyKey) {
        return Optional.empty();
    }

    /** A reputation view that has flagged nothing — X1 degrades cleanly to 0 (the §4B cut default). */
    static ReputationView empty() {
        return new ReputationView() {
            @Override
            public Optional<Long> lastFlagEpochMs(String counterpartyKey) {
                return Optional.empty();
            }
        };
    }
}
