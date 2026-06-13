package com.cy.diakritis.engine.store;

import java.util.List;
import java.util.Optional;

/**
 * Read-only access to the behavioural observation store: per-{@code (account, kind, value)}
 * first/last-seen timestamps of device, IP, platform, session and counterparty values, plus the
 * alias-resolution history that the SIM-swap signal (P1) needs.
 *
 * <p>This feeds the M1 recency features (D1/D4/D10/D15) — "never seen" maps to the model's missing
 * sentinel — and the structural signals G1 (unfamiliar geo), G2 (new network), D1 (device-age
 * decay), D2 (platform anomaly) and P1 (alias re-point).
 *
 * <p>Kinds are uppercase tokens (e.g. {@code "DEVICE"}, {@code "IP"}, {@code "GEO"},
 * {@code "NETWORK"}, {@code "PLATFORM"}, {@code "ALIAS"}). Timestamps are epoch-millis. An empty
 * {@link Optional} or empty {@link List} means the value was never observed.
 */
public interface ObservationsView {

    /** Epoch-millis a {@code (kind, value)} pair for this account was last observed, empty if never. */
    Optional<Long> lastSeenEpochMs(String accountId, String kind, String value);

    /** Epoch-millis a {@code (kind, value)} pair for this account was first observed, empty if never. */
    default Optional<Long> firstSeenEpochMs(String accountId, String kind, String value) {
        return Optional.empty();
    }

    /**
     * Has this account ever observed any value of the given {@code kind}? Distinguishes a cold-start
     * account (which should not be punished for a "new" value) from one that has a baseline of values
     * of this kind. Default: false (nothing observed).
     */
    default boolean hasAnyOfKind(String accountId, String kind) {
        return false;
    }

    /**
     * The set of distinct values this account has observed of the given {@code kind}. Used by G1/G2/D2
     * to decide whether the current value is genuinely new to a non-empty history. Default: empty.
     */
    default List<String> distinctValuesOfKind(String accountId, String kind) {
        return List.of();
    }

    /**
     * The account ref an {@code alias} (e.g. an MSISDN / e-mail) previously resolved to for this
     * account, if it has resolved to one before. P1 fires when the alias now resolves to a different
     * account than its own resolution history — the SIM-swap / alias-re-point tell. Empty when the
     * alias has no prior resolution on record. Default: empty.
     */
    default Optional<String> lastResolvedAccountRefForAlias(String accountId, String alias) {
        return Optional.empty();
    }

    /** An {@link ObservationsView} that has never observed anything (cold start / test default). */
    static ObservationsView empty() {
        return new ObservationsView() {
            @Override
            public Optional<Long> lastSeenEpochMs(String accountId, String kind, String value) {
                return Optional.empty();
            }
        };
    }
}
