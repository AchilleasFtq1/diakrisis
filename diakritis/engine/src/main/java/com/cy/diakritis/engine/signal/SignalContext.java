package com.cy.diakritis.engine.signal;

import com.cy.diakritis.common.dto.ActionEvent;
import com.cy.diakritis.common.dto.BeneficiaryAddPayload;
import com.cy.diakritis.common.dto.Counterparty;
import com.cy.diakritis.common.dto.TransferPayload;
import com.cy.diakritis.engine.store.FeatureStore;
import com.cy.diakritis.engine.store.GeoResolver;
import com.cy.diakritis.engine.store.ObservationsView;
import com.cy.diakritis.engine.store.PostureView;
import com.cy.diakritis.engine.store.ReputationView;
import com.cy.diakritis.engine.store.RuntimeState;

import java.time.Instant;

/**
 * Everything a {@link Signal} needs to evaluate one event, assembled once by the pipeline and
 * passed read-only to every signal. Pre-resolved monetary amounts (in cents) and the counterparty
 * key are computed once here so individual signals never re-derive them.
 *
 * <p>The geo / reputation seams are nullable-by-default: the back-compatible constructor fills them
 * with their resilient empty implementations so an older caller (or a test that only cares about the
 * deterministic core) gets a fully-functional context whose geo and X1 signals simply score 0. The
 * M2 model scorer is injected into the pipeline (like M1), not carried here.
 *
 * <p>{@code lineCounterparty} is the §4A per-line override: in a mass-payment batch the event payload
 * is the whole batch, but each line is scored against its OWN counterparty (B1/B5/A3/P1). The
 * pipeline sets this to the current line so counterparty-shaped signals read the line, not the batch.
 * For a single transfer it is null and {@link #counterparty()} derives the payee from the payload.
 *
 * @param event                 the action under evaluation
 * @param store                 offline feature baselines (read-only)
 * @param runtime               in-process rolling 24h / session state
 * @param posture               rolling account risk posture
 * @param obs                   behavioural observation store
 * @param geo                   IP→country resolver (G1/G2)
 * @param reputation            cross-account counterparty reputation (X1)
 * @param lineCounterparty      per-line counterparty override for batch scoring (null for transfers)
 * @param cpKey                 resolved counterparty key for this event/line
 * @param logicalAmountCents    {@code max(thisAmount, Σ24h to cpKey)} in euro-cents
 * @param amountCents           this event's/line's own amount in euro-cents
 * @param availableBalanceCents available balance in euro-cents
 * @param now                   decision timestamp
 */
public record SignalContext(
        ActionEvent event,
        FeatureStore store,
        RuntimeState runtime,
        PostureView posture,
        ObservationsView obs,
        GeoResolver geo,
        ReputationView reputation,
        Counterparty lineCounterparty,
        String cpKey,
        long logicalAmountCents,
        long amountCents,
        long availableBalanceCents,
        Instant now
) {

    /**
     * Full single-action constructor (no per-line override). The geo / reputation seams may be
     * supplied; the batch line counterparty defaults to null so {@link #counterparty()} reads the
     * payee from the payload.
     */
    public SignalContext(
            ActionEvent event,
            FeatureStore store,
            RuntimeState runtime,
            PostureView posture,
            ObservationsView obs,
            GeoResolver geo,
            ReputationView reputation,
            String cpKey,
            long logicalAmountCents,
            long amountCents,
            long availableBalanceCents,
            Instant now) {
        this(event, store, runtime, posture, obs, geo, reputation, null,
                cpKey, logicalAmountCents, amountCents, availableBalanceCents, now);
    }

    /**
     * Back-compatible constructor without the geo / reputation seams. They default to their resilient
     * empty implementations, so geo and X1 score 0 and the deterministic core is unaffected.
     */
    public SignalContext(
            ActionEvent event,
            FeatureStore store,
            RuntimeState runtime,
            PostureView posture,
            ObservationsView obs,
            String cpKey,
            long logicalAmountCents,
            long amountCents,
            long availableBalanceCents,
            Instant now) {
        this(event, store, runtime, posture, obs,
                GeoResolver.unknownAll(), ReputationView.empty(), null,
                cpKey, logicalAmountCents, amountCents, availableBalanceCents, now);
    }

    /** Convenience: the account id of the event under evaluation. */
    public String accountId() {
        return event.accountId();
    }

    /**
     * The counterparty this context is scoring: the per-line override when set (batch scoring), else
     * the payee derived from the event payload (single transfer / beneficiary add), else null
     * (non-monetary actions). The single resolution point for every counterparty-shaped signal.
     */
    public Counterparty counterparty() {
        if (lineCounterparty != null) {
            return lineCounterparty;
        }
        return switch (event.payload()) {
            case TransferPayload t -> t.counterparty();
            case BeneficiaryAddPayload b -> b.counterparty();
            default -> null;
        };
    }
}
