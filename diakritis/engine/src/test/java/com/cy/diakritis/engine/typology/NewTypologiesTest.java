package com.cy.diakritis.engine.typology;

import com.cy.diakritis.common.dto.ActionEvent;
import com.cy.diakritis.common.dto.Addressing;
import com.cy.diakritis.common.dto.Counterparty;
import com.cy.diakritis.common.dto.Rail;
import com.cy.diakritis.engine.Events;
import com.cy.diakritis.engine.FakeFeatureStore;
import com.cy.diakritis.engine.signal.Identity;
import com.cy.diakritis.engine.signal.SignalContext;
import com.cy.diakritis.engine.store.ObservationsView;
import com.cy.diakritis.engine.store.PostureView;
import com.cy.diakritis.engine.store.RuntimeState;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies the Wave-5 typologies (Ty3 purchase-scam, Ty6 payroll-redirection, Ty7 mule fan-out). */
class NewTypologiesTest {

    private final TypologyEvaluator evaluator = new TypologyEvaluator();
    private final Instant now = Instant.parse("2026-06-13T12:00:00Z");

    private SignalContext ctxFor(ActionEvent event, long amountCents) {
        Counterparty cp = switch (event.payload()) {
            case com.cy.diakritis.common.dto.TransferPayload t -> t.counterparty();
            default -> null;
        };
        String cpKey = cp != null ? Identity.counterpartyKey(cp) : "NONE";
        return new SignalContext(event, new FakeFeatureStore(), new RuntimeState(),
                PostureView.empty(now.toEpochMilli()), ObservationsView.empty(),
                cpKey, amountCents, amountCents, 9_000_00L, now);
    }

    @Test
    void ty3PurchaseScamFiresOnNewPayeeRealtimeRailModestAmount() {
        // P2P (real-time rail), brand-new payee, modest €150 → purchase scam.
        ActionEvent p2p = Events.transferWithSession("ty3", "acc",
                new Counterparty(Addressing.IBAN, "CYNEW", "CYNEW", null, "Seller", null),
                150, 5000, Rail.P2P, Events.session("s", now, "203.0.113.7", "dev",
                        com.cy.diakritis.common.dto.Platform.IOS));
        SignalContext modest = ctxFor(p2p, 15_000L);
        assertTrue(evaluator.evaluate(values("B1", 1.0), modest).contains(Typologies.PURCHASE_SCAM),
                "Ty3 fires for a new payee on P2P at a modest amount");

        // Same but a large amount → not "modest" → no purchase-scam (would be a drain typology instead).
        SignalContext large = ctxFor(p2p, 5_000_00L);
        assertFalse(evaluator.evaluate(values("B1", 1.0), large).contains(Typologies.PURCHASE_SCAM),
                "Ty3 silent when the amount is not modest");

        // SEPA (revocable rail) → not a purchase scam even when new + modest.
        ActionEvent sepa = Events.transfer("ty3b", "acc",
                Events.payee("CYNEW", null, null), 150, 5000, Rail.SEPA, now);
        SignalContext sepaCtx = ctxFor(sepa, 15_000L);
        assertFalse(evaluator.evaluate(values("B1", 1.0), sepaCtx).contains(Typologies.PURCHASE_SCAM),
                "Ty3 silent on a revocable rail");
    }

    @Test
    void ty7MuleFanOutFiresOnHighShareAndDrain() {
        ActionEvent batch = Events.massPayment("ty7", "acc", "B", List.of(
                Events.line("L1", Events.payee("CY1", null, null), 100)),
                100, 9000, Rail.SEPA, now);
        SignalContext ctx = ctxFor(batch, 10_000L);
        assertTrue(evaluator.evaluate(values("MP1", 0.93, "MP4", 0.81), ctx)
                .contains(Typologies.MULE_FAN_OUT), "Ty7 fires when MP1>0.7 and MP4>0.6");

        // MP4 below threshold → no fan-out.
        assertFalse(evaluator.evaluate(values("MP1", 0.93, "MP4", 0.4), ctx)
                .contains(Typologies.MULE_FAN_OUT), "Ty7 silent when MP4 is below threshold");
        // MP1 below threshold → no fan-out.
        assertFalse(evaluator.evaluate(values("MP1", 0.3, "MP4", 0.81), ctx)
                .contains(Typologies.MULE_FAN_OUT), "Ty7 silent when MP1 is below threshold");
    }

    @Test
    void ty6PayrollRedirectionRequiresEstablishedPatternAndAChangedLine() {
        // Established batch pattern + at least one line with B5==1 → payroll redirection.
        assertTrue(evaluator.isPayrollRedirection(true, List.of(0.0, 1.0, 0.0)),
                "Ty6 fires with an established pattern and one changed-IBAN line");

        // Established pattern but no changed lines → no redirection.
        assertFalse(evaluator.isPayrollRedirection(true, List.of(0.0, 0.0)),
                "Ty6 silent with no changed lines");

        // Changed line but no established pattern (e.g. a first-time batch) → no redirection.
        assertFalse(evaluator.isPayrollRedirection(false, List.of(1.0)),
                "Ty6 silent without an established batch pattern");
    }

    private static Map<String, Double> values(Object... pairs) {
        Map<String, Double> map = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], ((Number) pairs[i + 1]).doubleValue());
        }
        return map;
    }
}
