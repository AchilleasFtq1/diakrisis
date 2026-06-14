package com.cy.diakritis.engine.pipeline;

import com.cy.diakritis.common.dto.ActionEvent;
import com.cy.diakritis.common.dto.Addressing;
import com.cy.diakritis.common.dto.Counterparty;
import com.cy.diakritis.common.dto.EngineVerdict;
import com.cy.diakritis.common.dto.Rail;
import com.cy.diakritis.common.dto.Verdict;
import com.cy.diakritis.engine.Events;
import com.cy.diakritis.engine.FakeFeatureStore;
import com.cy.diakritis.engine.FakeReputation;
import com.cy.diakritis.engine.m1.M1Scorer;
import com.cy.diakritis.engine.store.AccountStatsView;
import com.cy.diakritis.engine.store.GeoResolver;
import com.cy.diakritis.engine.store.ObservationsView;
import com.cy.diakritis.engine.store.PostureView;
import com.cy.diakritis.engine.store.ReputationView;
import com.cy.diakritis.engine.store.RuntimeState;
import com.cy.diakritis.engine.typology.TypologyEvaluator;
import com.cy.diakritis.engine.typology.Typologies;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end engine assertions for the Wave-5 cross-account, mule-fan-out and payroll-redirection
 * paths, scored through the full {@link ScoreEngine} (signals → typologies → bands → routing).
 */
class ScoreEngineWave5Test {

    private static final long DAY_MS = 24L * 60L * 60L * 1000L;
    private static final Path MODELS_DIR =
            Path.of("/Users/achilleaseftychiou/Documents/Projects/diakrisis/diakrisis-models");

    private final Instant now = Instant.parse("2026-06-13T12:00:00Z");
    private final M1Scorer m1 = new M1Scorer(MODELS_DIR);
    private final ScoreEngine engine = new ScoreEngine(m1, new TypologyEvaluator());

    private static double signalValue(EngineVerdict verdict, String id) {
        return verdict.signals().stream()
                .filter(s -> s.id().equals(id))
                .mapToDouble(com.cy.diakritis.common.dto.Signal::value)
                .findFirst().orElse(0.0);
    }

    @Test
    void x1FlaggedDestinationEscalatesAndCarriesReason() {
        String cpKey = "IBAN|MULE-9";
        Counterparty cp = new Counterparty(Addressing.IBAN, cpKey, cpKey, null, "Mule", null);
        // For the sender the payee is new and the amount modest; only the cross-account flag is heavy.
        ActionEvent event = Events.transferWithSession("x1e", "acc-X", cp, 800, 9000, Rail.SEPA,
                Events.session("s", now, "203.0.113.7", "dev", com.cy.diakritis.common.dto.Platform.IOS));
        FakeFeatureStore store = new FakeFeatureStore()
                .seedStats("acc-X", new AccountStatsView(10_000L, 2_000L, 10_000L, 2_000L, 50L,
                        false, false, List.of()));
        ReputationView reputation = new FakeReputation().flag(cpKey, now.toEpochMilli() - 30_000L, "BLOCK");

        ScoreResult flagged = engine.score(event, store, new RuntimeState(),
                PostureView.empty(now.toEpochMilli()), ObservationsView.empty(),
                GeoResolver.unknownAll(), reputation, now);
        ScoreResult clean = engine.score(event, store, new RuntimeState(),
                PostureView.empty(now.toEpochMilli()), ObservationsView.empty(),
                GeoResolver.unknownAll(), ReputationView.empty(), now);

        assertTrue(signalValue(flagged.engineVerdict(), "X1") > 0.5, "X1 must fire on the flagged dest");
        assertTrue(flagged.engineVerdict().score() > clean.engineVerdict().score(),
                "a flagged destination must score strictly higher than a clean one");
        assertNotEquals(Verdict.ALLOW, flagged.engineVerdict().decision(),
                "a recently-block-flagged destination must not be a clean ALLOW");
        assertEquals(ReasonCodes.XACCT, flagged.reasonCode(), "X1 drives the DKR-XACCT reason");
    }

    @Test
    void muleFanOutBatchHoldsOnRetailAccount() {
        // A retail account spraying many brand-new payees that drain the balance → Ty7 mule fan-out.
        ActionEvent batch = Events.massPayment("fanout", "acc-mule", "B-FANOUT", List.of(
                Events.line("L1", Events.payee("CY1", null, null), 2000),
                Events.line("L2", Events.payee("CY2", null, null), 2000),
                Events.line("L3", Events.payee("CY3", null, null), 2000),
                Events.line("L4", Events.payee("CY4", null, null), 2000)),
                8000, 8500, Rail.SEPA, now);
        // Retail account (not business) so the batch is NOT force-routed to approval; the typology must
        // be what holds it.
        FakeFeatureStore store = new FakeFeatureStore()
                .seedStats("acc-mule", new AccountStatsView(0, 0, 0, 0, 0, false, false, List.of()));

        ScoreResult result = engine.score(batch, store, new RuntimeState(),
                PostureView.empty(now.toEpochMilli()), ObservationsView.empty(), now);

        assertTrue(result.engineVerdict().typologies().contains(Typologies.MULE_FAN_OUT),
                "batch must name mule_fan_out but was " + result.engineVerdict().typologies());
        assertTrue(result.engineVerdict().decision() == Verdict.HOLD
                        || result.engineVerdict().decision() == Verdict.BLOCK,
                "mule fan-out batch must HOLD/BLOCK but was " + result.engineVerdict().decision());
    }

    @Test
    void muleFanOutBatchStaysBlockOnBusinessAccount() {
        // Regression for the business-override downgrade bug: a maximally-stacked mule fan-out whose
        // worst line reaches a confirmed-fraud BLOCK (raw ≥ 90, MULE_FAN_OUT) on a BUSINESS account must
        // STAY BLOCK. Previously the business four-eyes route unconditionally rewrote it to a releasable
        // REQUIRE_APPROVAL, letting a second approver release confirmed fraud.
        List<com.cy.diakritis.common.dto.BatchItem> lines = new java.util.ArrayList<>();
        for (int i = 0; i < 30; i++) {
            lines.add(Events.line("L" + i, Events.payee("MULE-" + i, null, null), 2900));
        }
        // 30 brand-new payees draining ~99.4% of the balance → MP1≈1.0, MP4≈1.0, outsized total → MP2
        // saturates; the worst line's raw score crosses 90 → confirmed-fraud BLOCK.
        ActionEvent batch = Events.massPayment("biz-fanout", "acc-biz", "B-BIZ-FANOUT", lines,
                87000, 87500, Rail.SEPA, now);
        FakeFeatureStore store = new FakeFeatureStore()
                .seedStats("acc-biz", new AccountStatsView(10_000L, 1_000L, 10_000L, 1_000L, 200L,
                        true, false, List.of()));

        ScoreResult result = engine.score(batch, store, new RuntimeState(),
                PostureView.empty(now.toEpochMilli()), ObservationsView.empty(), now);

        assertTrue(result.engineVerdict().typologies().contains(Typologies.MULE_FAN_OUT),
                "business mule fan-out must name mule_fan_out but was " + result.engineVerdict().typologies());
        assertEquals(Verdict.BLOCK, result.engineVerdict().decision(),
                "a confirmed-fraud (raw≥90) business mule fan-out must STAY BLOCK, not downgrade to "
                        + "REQUIRE_APPROVAL — was " + result.engineVerdict().decision()
                        + " score=" + result.engineVerdict().score());
    }

    @Test
    void payrollRedirectionQuarantinesChangedLineAndRoutesToApproval() {
        // Established business payroll: two recurring employees + one line whose IBAN changed (B5).
        String empAName = "Alice Worker";
        String empBName = "Bob Worker";
        String changedName = "Carol Worker";
        String carolOldIban = "CY-CAROL-OLD";
        String carolNewIban = "CY-CAROL-NEW";

        FakeFeatureStore store = new FakeFeatureStore()
                .seedStats("acc-corp", new AccountStatsView(150_000L, 20_000L, 150_000L, 20_000L, 300L,
                        true, true, List.of("approver-biz")))
                .seedBaseline("acc-corp", "CY-ALICE", 12L, 150_000L, now.toEpochMilli() - 200 * DAY_MS,
                        List.of())
                .seedBaseline("acc-corp", "CY-BOB", 12L, 150_000L, now.toEpochMilli() - 200 * DAY_MS,
                        List.of())
                // Carol established under her OLD iban; the new line pays her NEW iban under her name.
                .seedByName("acc-corp",
                        com.cy.diakritis.engine.signal.Identity.normalizeName(changedName),
                        new com.cy.diakritis.engine.store.CounterpartyByNameView(
                                com.cy.diakritis.engine.signal.Identity.normalizeName(changedName),
                                changedName, carolOldIban, carolOldIban, 6L, 150_000L,
                                now.toEpochMilli() - 200 * DAY_MS, now.toEpochMilli() - 30 * DAY_MS));

        Counterparty alice = new Counterparty(Addressing.IBAN, "CY-ALICE", "CY-ALICE", empAName, empAName, null);
        Counterparty bob = new Counterparty(Addressing.IBAN, "CY-BOB", "CY-BOB", empBName, empBName, null);
        Counterparty carol = new Counterparty(Addressing.IBAN, carolNewIban, carolNewIban, changedName,
                changedName, null);

        ActionEvent batch = Events.massPayment("payroll", "acc-corp", "PAYROLL-2026-06", List.of(
                Events.line("L01", alice, 1500),
                Events.line("L02", carol, 1500),
                Events.line("L03", bob, 1500)),
                4500, 90000, Rail.SEPA, now);

        ScoreResult result = engine.score(batch, store, new RuntimeState(),
                PostureView.empty(now.toEpochMilli()), ObservationsView.empty(), now);

        assertTrue(result.engineVerdict().typologies().contains(Typologies.PAYROLL_REDIRECTION),
                "batch must name payroll_redirection but was " + result.engineVerdict().typologies());
        // Business account → batch routes to four-eyes approval regardless of band.
        assertEquals(Verdict.REQUIRE_APPROVAL, result.engineVerdict().decision(),
                "corporate batch must route to REQUIRE_APPROVAL");
        // The changed line (L02) is quarantined (HELD/BLOCKED) while the clean lines are not.
        com.cy.diakritis.common.dto.ItemResult l02 = result.items().stream()
                .filter(i -> i.itemId().equals("L02")).findFirst().orElseThrow();
        com.cy.diakritis.common.dto.ItemResult l01 = result.items().stream()
                .filter(i -> i.itemId().equals("L01")).findFirst().orElseThrow();
        assertEquals(1.0, l02.signals().stream().filter(s -> s.id().equals("B5"))
                .mapToDouble(com.cy.diakritis.common.dto.Signal::value).findFirst().orElse(0.0), 1e-9,
                "the redirected line must trip B5");
        assertTrue(l02.decision() == Verdict.HOLD || l02.decision() == Verdict.BLOCK,
                "the changed line must be quarantined but was " + l02.decision());
        assertTrue(l01.decision() == Verdict.ALLOW || l01.decision() == Verdict.CONFIRM,
                "a clean payroll line must not be quarantined but was " + l01.decision());
    }
}
