package com.cy.diakritis.decision.store;

import com.cy.diakritis.common.persistence.ObservationItem;
import com.cy.diakritis.decision.repo.ObservationRepository;
import com.cy.diakritis.engine.store.ObservationsView;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * DynamoDB-backed {@link ObservationsView}: last-seen lookups over the {@code Observations} table.
 * A never-observed value returns an empty {@link Optional}, which the M1 recency features map to the
 * model's missing sentinel — that is real cold-start behaviour, not a stub.
 */
@Component
public class DynamoObservationsView implements ObservationsView {

    private final ObservationRepository observationRepository;

    public DynamoObservationsView(ObservationRepository observationRepository) {
        this.observationRepository = observationRepository;
    }

    @Override
    public Optional<Long> lastSeenEpochMs(String accountId, String kind, String value) {
        return observationRepository.find(accountId, kind, value)
                .map(ObservationItem::getLastSeenEpochMs);
    }
}
