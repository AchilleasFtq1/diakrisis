package com.cy.diakritis.decision.store;

import com.cy.diakritis.common.persistence.ObservationItem;
import com.cy.diakritis.decision.repo.ObservationRepository;
import com.cy.diakritis.engine.store.ObservationsView;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * DynamoDB-backed {@link ObservationsView} over the {@code Observations} table. It is the read seam
 * the structural signals consume: G1 (unfamiliar geo) and G2 (new network) read the familiar
 * country / network baselines via {@link #distinctValuesOfKind}; D1 (device-age decay) reads a
 * device's first sighting via {@link #firstSeenEpochMs} and the account's device baseline via
 * {@link #hasAnyOfKind}; D2 (platform anomaly) reads the platform baseline; P1 (alias re-point)
 * reads the alias-resolution history via {@link #lastResolvedAccountRefForAlias}.
 *
 * <p>Every lookup is total: a never-observed value returns an empty {@link Optional} / empty
 * {@link List}, which is real cold-start behaviour (the signals stay silent rather than inventing
 * risk), not a stub. The {@code ALIAS} kind stores the resolved account ref on the observation's
 * {@code lastResolvedAccountRef} column, so P1 can compare the prior resolution against the current.
 */
@Component
public class DynamoObservationsView implements ObservationsView {

    /** The observation kind under which an alias (MSISDN / e-mail) → account resolution is recorded. */
    public static final String KIND_ALIAS = "ALIAS";

    private final ObservationRepository observationRepository;

    public DynamoObservationsView(ObservationRepository observationRepository) {
        this.observationRepository = observationRepository;
    }

    @Override
    public Optional<Long> lastSeenEpochMs(String accountId, String kind, String value) {
        return observationRepository.find(accountId, kind, value)
                .map(ObservationItem::getLastSeenEpochMs);
    }

    @Override
    public Optional<Long> firstSeenEpochMs(String accountId, String kind, String value) {
        return observationRepository.find(accountId, kind, value)
                .map(ObservationItem::getFirstSeenEpochMs);
    }

    @Override
    public boolean hasAnyOfKind(String accountId, String kind) {
        return !observationRepository.queryByKind(accountId, kind).isEmpty();
    }

    @Override
    public List<String> distinctValuesOfKind(String accountId, String kind) {
        return observationRepository.queryByKind(accountId, kind).stream()
                .map(ObservationItem::getValue)
                .filter(v -> v != null)
                .distinct()
                .toList();
    }

    @Override
    public Optional<String> lastResolvedAccountRefForAlias(String accountId, String alias) {
        return observationRepository.find(accountId, KIND_ALIAS, alias)
                .map(ObservationItem::getLastResolvedAccountRef)
                .filter(ref -> ref != null && !ref.isBlank());
    }
}
