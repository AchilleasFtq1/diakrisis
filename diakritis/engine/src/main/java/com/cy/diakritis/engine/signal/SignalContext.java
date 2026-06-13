package com.cy.diakritis.engine.signal;

import com.cy.diakritis.common.dto.ActionEvent;
import com.cy.diakritis.engine.store.FeatureStore;
import com.cy.diakritis.engine.store.ObservationsView;
import com.cy.diakritis.engine.store.PostureView;
import com.cy.diakritis.engine.store.RuntimeState;

import java.time.Instant;

/**
 * Everything a {@link Signal} needs to evaluate one event, assembled once by the pipeline and
 * passed read-only to every signal. Pre-resolved monetary amounts (in cents) and the counterparty
 * key are computed once here so individual signals never re-derive them.
 *
 * @param event                the action under evaluation
 * @param store                offline feature baselines (read-only)
 * @param runtime              in-process rolling 24h / session state
 * @param posture              rolling 72h account risk posture
 * @param obs                  behavioural observation store
 * @param cpKey                resolved counterparty key for this event
 * @param logicalAmountCents   {@code max(thisAmount, Σ24h to cpKey)} in euro-cents
 * @param amountCents          this event's own amount in euro-cents
 * @param availableBalanceCents available balance in euro-cents
 * @param now                  decision timestamp
 */
public record SignalContext(
        ActionEvent event,
        FeatureStore store,
        RuntimeState runtime,
        PostureView posture,
        ObservationsView obs,
        String cpKey,
        long logicalAmountCents,
        long amountCents,
        long availableBalanceCents,
        Instant now
) {

    /** Convenience: the account id of the event under evaluation. */
    public String accountId() {
        return event.accountId();
    }
}
