package com.cy.diakritis.engine.typology;

import com.cy.diakritis.common.dto.ActionEvent;
import com.cy.diakritis.common.dto.Counterparty;
import com.cy.diakritis.common.dto.Rail;
import com.cy.diakritis.engine.Events;
import com.cy.diakritis.engine.FakeFeatureStore;
import com.cy.diakritis.engine.band.Weights;
import com.cy.diakritis.engine.signal.Identity;
import com.cy.diakritis.engine.signal.SignalContext;
import com.cy.diakritis.engine.store.CounterpartyByNameView;
import com.cy.diakritis.engine.store.ObservationsView;
import com.cy.diakritis.engine.store.PostureView;
import com.cy.diakritis.engine.store.RuntimeState;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies each typology rule fires exactly under its specified signal conditions. */
class TypologyEvaluatorTest {

    private static final long DAY_MS = 24L * 60L * 60L * 1000L;
    private final TypologyEvaluator evaluator = new TypologyEvaluator();
    private final Instant now = Instant.parse("2026-06-13T12:00:00Z");

    private SignalContext ctxFor(ActionEvent event, FakeFeatureStore store) {
        Counterparty cp = switch (event.payload()) {
            case com.cy.diakritis.common.dto.TransferPayload t -> t.counterparty();
            default -> null;
        };
        String cpKey = cp != null ? Identity.counterpartyKey(cp) : "NONE";
        return new SignalContext(event, store, new RuntimeState(),
                PostureView.empty(now.toEpochMilli()), ObservationsView.empty(),
                cpKey, 0L, 0L, 0L, now);
    }

    @Test
    void ty5LiquidationKillChainFiresOnK1B1A2() {
        ActionEvent event = Events.transfer("k", "acc-B",
                Events.payee("CY99NEW", null, now.minusSeconds(120)), 4850, 4980, Rail.SEPA, now);
        SignalContext ctx = ctxFor(event, new FakeFeatureStore());
        Map<String, Double> values = values("K1", 0.95, "B1", 1.0, "A2", 0.86);
        assertTrue(evaluator.evaluate(values, ctx).contains(Typologies.LIQUIDATION_KILL_CHAIN));

        // K1 below threshold → does not fire.
        assertFalse(evaluator.evaluate(values("K1", 0.5, "B1", 1.0, "A2", 0.86), ctx)
                .contains(Typologies.LIQUIDATION_KILL_CHAIN));
    }

    @Test
    void ty4RomanceRepeatVictimFiresOnV2AndB2() {
        ActionEvent event = Events.transfer("r", "acc-C",
                Events.payee("CY33", null, now.minusSeconds(60)), 2000, 9000, Rail.SEPA, now);
        SignalContext ctx = ctxFor(event, new FakeFeatureStore());
        assertTrue(evaluator.evaluate(values("V2", 1.0, "B2", 0.55), ctx)
                .contains(Typologies.ROMANCE_REPEAT_VICTIM));
        // B2 not high enough → no fire.
        assertFalse(evaluator.evaluate(values("V2", 1.0, "B2", 0.3), ctx)
                .contains(Typologies.ROMANCE_REPEAT_VICTIM));
    }

    @Test
    void ty1SafeAccountScamFiresOnB1A2B3() {
        ActionEvent event = Events.transfer("s", "acc-X",
                Events.payee("CY44", null, now.minusSeconds(30)), 8000, 9000, Rail.SEPA, now);
        SignalContext ctx = ctxFor(event, new FakeFeatureStore());
        assertTrue(evaluator.evaluate(values("B1", 1.0, "A2", 0.85, "B3", 1.0), ctx)
                .contains(Typologies.SAFE_ACCOUNT_SCAM));
        // A2 not above 0.7 → no fire.
        assertFalse(evaluator.evaluate(values("B1", 1.0, "A2", 0.6, "B3", 1.0), ctx)
                .contains(Typologies.SAFE_ACCOUNT_SCAM));
    }

    @Test
    void ty2InvoiceRedirectionRequiresEstablishedCounterparty() {
        String supplierName = "Acme Supplies Ltd";
        Counterparty cp = Events.payee("CY00NEWIBAN", supplierName, null);
        ActionEvent event = Events.transfer("i", "acc-A", cp, 4200, 8000, Rail.SEPA, now);

        // Established: payCount >= 3 and age >= 30 days, different established key → fires.
        FakeFeatureStore established = new FakeFeatureStore().seedByName("acc-A",
                Identity.normalizeName(supplierName),
                new CounterpartyByNameView(Identity.normalizeName(supplierName), supplierName,
                        "CY00OLDIBAN", "CY00OLDIBAN",
                        Weights.TY2_ESTABLISHED_MIN_PAYMENTS, 150_000L,
                        now.toEpochMilli() - 40L * DAY_MS, now.toEpochMilli() - DAY_MS));
        SignalContext ctxEstablished = ctxFor(event, established);
        assertTrue(evaluator.evaluate(values("B5", 1.0, "A3", 0.4), ctxEstablished)
                .contains(Typologies.INVOICE_REDIRECTION));

        // Not established (too few payments) → does not fire even with B5 and A3.
        FakeFeatureStore fresh = new FakeFeatureStore().seedByName("acc-A",
                Identity.normalizeName(supplierName),
                new CounterpartyByNameView(Identity.normalizeName(supplierName), supplierName,
                        "CY00OLDIBAN", "CY00OLDIBAN",
                        1L, 150_000L, now.toEpochMilli() - 40L * DAY_MS, now.toEpochMilli() - DAY_MS));
        SignalContext ctxFresh = ctxFor(event, fresh);
        assertFalse(evaluator.evaluate(values("B5", 1.0, "A3", 0.4), ctxFresh)
                .contains(Typologies.INVOICE_REDIRECTION));
    }

    private static Map<String, Double> values(Object... pairs) {
        Map<String, Double> map = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], ((Number) pairs[i + 1]).doubleValue());
        }
        return map;
    }
}
