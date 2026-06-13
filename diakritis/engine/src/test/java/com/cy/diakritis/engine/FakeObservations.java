package com.cy.diakritis.engine;

import com.cy.diakritis.engine.store.ObservationsView;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * In-memory {@link ObservationsView} for engine tests. Lets a test seed first/last-seen timestamps
 * per {@code (account, kind, value)}, the set of distinct values per kind (for G1/G2/D2/C1), and
 * alias-resolution history (for P1) — mirroring the shapes the DynamoDB implementation exposes.
 */
public final class FakeObservations implements ObservationsView {

    private record Key(String accountId, String kind, String value) {
    }

    private final Map<Key, Long> firstSeen = new LinkedHashMap<>();
    private final Map<Key, Long> lastSeen = new LinkedHashMap<>();
    private final Map<String, List<String>> valuesByKind = new LinkedHashMap<>();
    private final Map<String, String> aliasResolution = new LinkedHashMap<>();

    public FakeObservations seen(String accountId, String kind, String value,
                                 long firstSeenEpochMs, long lastSeenEpochMs) {
        Key key = new Key(accountId, kind, value);
        firstSeen.put(key, firstSeenEpochMs);
        lastSeen.put(key, lastSeenEpochMs);
        valuesByKind.computeIfAbsent(accountId + "::" + kind, k -> new ArrayList<>());
        List<String> values = valuesByKind.get(accountId + "::" + kind);
        if (!values.contains(value)) {
            values.add(value);
        }
        return this;
    }

    public FakeObservations aliasResolvedTo(String accountId, String alias, String resolvedAccountRef) {
        aliasResolution.put(accountId + "::" + alias, resolvedAccountRef);
        return this;
    }

    @Override
    public Optional<Long> lastSeenEpochMs(String accountId, String kind, String value) {
        return Optional.ofNullable(lastSeen.get(new Key(accountId, kind, value)));
    }

    @Override
    public Optional<Long> firstSeenEpochMs(String accountId, String kind, String value) {
        return Optional.ofNullable(firstSeen.get(new Key(accountId, kind, value)));
    }

    @Override
    public boolean hasAnyOfKind(String accountId, String kind) {
        List<String> values = valuesByKind.get(accountId + "::" + kind);
        return values != null && !values.isEmpty();
    }

    @Override
    public List<String> distinctValuesOfKind(String accountId, String kind) {
        List<String> values = valuesByKind.get(accountId + "::" + kind);
        return values == null ? List.of() : List.copyOf(values);
    }

    @Override
    public Optional<String> lastResolvedAccountRefForAlias(String accountId, String alias) {
        return Optional.ofNullable(aliasResolution.get(accountId + "::" + alias));
    }
}
