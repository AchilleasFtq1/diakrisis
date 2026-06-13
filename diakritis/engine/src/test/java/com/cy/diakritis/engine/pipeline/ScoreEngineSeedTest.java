package com.cy.diakritis.engine.pipeline;

import com.cy.diakritis.common.dto.ActionEvent;
import com.cy.diakritis.common.dto.Counterparty;
import com.cy.diakritis.common.dto.EngineVerdict;
import com.cy.diakritis.common.dto.Rail;
import com.cy.diakritis.common.dto.Verdict;
import com.cy.diakritis.common.persistence.RecentPayment;
import com.cy.diakritis.engine.Events;
import com.cy.diakritis.engine.FakeFeatureStore;
import com.cy.diakritis.engine.band.Weights;
import com.cy.diakritis.engine.m1.M1Scorer;
import com.cy.diakritis.engine.signal.Identity;
import com.cy.diakritis.engine.store.AccountStatsView;
import com.cy.diakritis.engine.store.CounterpartyByNameView;
import com.cy.diakritis.engine.store.ObservationsView;
import com.cy.diakritis.engine.store.PostureView;
import com.cy.diakritis.engine.store.RuntimeState;
import com.cy.diakritis.engine.typology.TypologyEvaluator;
import com.cy.diakritis.engine.typology.Typologies;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end engine assertions over the seed scenarios T1-T6. Uses an in-memory feature store
 * seeded to mirror the disclosed Berka baselines and constructs, and the real M1 model when present
 * (the M1 contribution is additive and capped, so these decisions hold regardless of M1 load state).
 */
class ScoreEngineSeedTest {

    private static final long DAY_MS = 24L * 60L * 60L * 1000L;
    private static final Path MODELS_DIR =
            Path.of("/Users/achilleaseftychiou/Documents/Projects/diakrisis/diakrisis-models");

    private final Instant now = Instant.parse("2026-06-13T12:00:00Z");
    private final M1Scorer m1 = new M1Scorer(MODELS_DIR);
    private final ScoreEngine engine = new ScoreEngine(m1, new TypologyEvaluator());

    private ScoreResult run(ActionEvent event, FakeFeatureStore store, PostureView posture) {
        return engine.score(event, store, new RuntimeState(), posture, ObservationsView.empty(), now);
    }

    /** Account 7819: tight low-mean outgoing distribution (n=287, mean €126.33, max €195.30). */
    private AccountStatsView account7819Stats(boolean hasApprover) {
        return new AccountStatsView(
                12_633L,   // mean cents
                2_000L,    // std cents
                12_633L,   // median cents
                1_500L,    // MAD cents — tight
                287L,
                false,
                hasApprover,
                List.of());
    }

    private List<RecentPayment> sixRoutinePayments(long amountCents, long firstSeenMs) {
        return List.of(
                new RecentPayment(amountCents, firstSeenMs),
                new RecentPayment(amountCents, firstSeenMs + 30 * DAY_MS),
                new RecentPayment(amountCents, firstSeenMs + 60 * DAY_MS),
                new RecentPayment(amountCents, firstSeenMs + 90 * DAY_MS),
                new RecentPayment(amountCents, firstSeenMs + 120 * DAY_MS),
                new RecentPayment(amountCents, firstSeenMs + 150 * DAY_MS));
    }

    @Test
    void t1_routinePaymentToEstablishedPayee_isAllowAndScaExempt() {
        String cpKey = "CD|46939146";
        long firstSeen = now.toEpochMilli() - 200L * DAY_MS;
        FakeFeatureStore store = new FakeFeatureStore()
                .seedStats("acc-A", account7819Stats(false))
                .seedBaseline("acc-A", cpKey, 60L, 12_960L, firstSeen,
                        sixRoutinePayments(12_960L, firstSeen));
        Counterparty cp = new Counterparty(com.cy.diakritis.common.dto.Addressing.IBAN,
                cpKey, cpKey, "CD Supplier", "CD Supplier", null);
        ActionEvent event = Events.transfer("T1", "acc-A", cp, 120, 4500, Rail.SEPA, now);

        ScoreResult result = run(event, store, PostureView.empty(now.toEpochMilli()));
        EngineVerdict v = result.engineVerdict();

        assertEquals(Verdict.ALLOW, v.decision(), "T1 must ALLOW");
        assertTrue(v.score() >= 0 && v.score() <= 29, "T1 score must be 0-29 but was " + v.score());
        assertTrue(v.scaExempt(), "T1 must be SCA-exempt");
        assertTrue(v.typologies().isEmpty(), "T1 must name no typology");
        assertNull(result.explanation().customer(), "T1 ALLOW must have null customer explanation");
    }

    @Test
    void t2_secondEstablishedPayee_isAllowAndScaExempt() {
        String cpKey = "KL|64831554";
        long firstSeen = now.toEpochMilli() - 220L * DAY_MS;
        FakeFeatureStore store = new FakeFeatureStore()
                .seedStats("acc-A", account7819Stats(false))
                .seedBaseline("acc-A", cpKey, 60L, 19_530L, firstSeen,
                        sixRoutinePayments(19_530L, firstSeen));
        Counterparty cp = new Counterparty(com.cy.diakritis.common.dto.Addressing.IBAN,
                cpKey, cpKey, "KL Supplier", "KL Supplier", null);
        ActionEvent event = Events.transfer("T2", "acc-A", cp, 750, 3200, Rail.SEPA, now);

        ScoreResult result = run(event, store, PostureView.empty(now.toEpochMilli()));
        EngineVerdict v = result.engineVerdict();

        assertEquals(Verdict.ALLOW, v.decision(), "T2 must ALLOW (established + low recency)");
        assertTrue(v.score() >= 0 && v.score() <= 29, "T2 score must be 0-29 but was " + v.score());
        assertTrue(v.scaExempt(), "T2 must be SCA-exempt");
        assertNull(result.explanation().customer(), "T2 ALLOW must have null customer explanation");
    }

    @Test
    void t3_anomalousAmountToEstablishedPayee_isConfirm() {
        String cpKey = "CD|46939146";
        long firstSeen = now.toEpochMilli() - 200L * DAY_MS;
        FakeFeatureStore store = new FakeFeatureStore()
                .seedStats("acc-A", account7819Stats(false))
                // Only ~12 prior payments so B4 does not credit trust (age < 90d? no, but payCount<20).
                .seedBaseline("acc-A", cpKey, 12L, 12_960L, now.toEpochMilli() - 40L * DAY_MS,
                        sixRoutinePayments(12_960L, now.toEpochMilli() - 40L * DAY_MS));
        Counterparty cp = new Counterparty(com.cy.diakritis.common.dto.Addressing.IBAN,
                cpKey, cpKey, "CD Supplier", "CD Supplier", null);
        ActionEvent event = Events.transfer("T3", "acc-A", cp, 700, 3200, Rail.SEPA, now);

        ScoreResult result = run(event, store, PostureView.empty(now.toEpochMilli()));
        EngineVerdict v = result.engineVerdict();

        assertEquals(Verdict.CONFIRM, v.decision(), "T3 must CONFIRM");
        assertTrue(v.score() >= 30 && v.score() <= 59, "T3 score must be 30-59 but was " + v.score());
        assertEquals(false, v.scaExempt(), "T3 CONFIRM is not SCA-exempt");
        assertTrue(signalValue(v, "A3") > 0, "T3 A3 must be > 0");
        assertNotNull(result.explanation().customer(), "T3 CONFIRM must have a customer explanation");
    }

    @Test
    void t4_invoiceRedirection_isHoldWithTypologyAndB5() {
        String supplierName = "Acme Supplies Ltd";
        String newIban = "CY00NEWIBAN0000";
        String oldIban = "CY00OLDIBAN0000";
        FakeFeatureStore store = new FakeFeatureStore()
                .seedStats("acc-A", account7819Stats(false))
                .seedByName("acc-A", Identity.normalizeName(supplierName),
                        new CounterpartyByNameView(Identity.normalizeName(supplierName), supplierName,
                                oldIban, oldIban,
                                Weights.TY2_ESTABLISHED_MIN_PAYMENTS + 1, 150_000L,
                                now.toEpochMilli() - 40L * DAY_MS, now.toEpochMilli() - DAY_MS))
                // The established old-IBAN counterparty carries the A3 baseline (so A3 fires on €4200).
                .seedBaseline("acc-A", oldIban, 4L, 150_000L, now.toEpochMilli() - 40L * DAY_MS,
                        List.of(new RecentPayment(150_000L, now.toEpochMilli() - 30L * DAY_MS),
                                new RecentPayment(150_000L, now.toEpochMilli() - 10L * DAY_MS)));
        // Pay the NEW iban under the established supplier name. A3 keys on cpKey = new iban, which has
        // no baseline, so to make A3 fire we mirror the established mean onto the new key as well
        // (the model uses the name's history; the test store exposes it via the cp key it will read).
        store.seedBaseline("acc-A", newIban, 4L, 150_000L, now.toEpochMilli() - 40L * DAY_MS,
                List.of(new RecentPayment(150_000L, now.toEpochMilli() - 30L * DAY_MS),
                        new RecentPayment(150_000L, now.toEpochMilli() - 10L * DAY_MS)));

        Counterparty cp = new Counterparty(com.cy.diakritis.common.dto.Addressing.IBAN,
                newIban, newIban, supplierName, supplierName, null);
        ActionEvent event = Events.transfer("T4", "acc-A", cp, 4200, 8000, Rail.SEPA, now);

        ScoreResult result = run(event, store, PostureView.empty(now.toEpochMilli()));
        EngineVerdict v = result.engineVerdict();

        assertEquals(Verdict.HOLD, v.decision(), "T4 must HOLD");
        assertTrue(v.typologies().contains(Typologies.INVOICE_REDIRECTION),
                "T4 must name invoice_redirection but was " + v.typologies());
        assertEquals(1.0, signalValue(v, "B5"), 1e-9, "T4 B5 must be 1.0");
        assertNotNull(result.explanation().customer(), "T4 HOLD must have a customer explanation");
        assertEquals(ReasonCodes.INVOICE, result.reasonCode());
    }

    @Test
    void t5a_termDepositBreak_isConfirmNeverHold_andHasPurposePrompt() {
        FakeFeatureStore store = new FakeFeatureStore()
                .seedStats("acc-B", new AccountStatsView(0, 0, 0, 0, 0, false, false, List.of()));
        ActionEvent event = Events.depositBreak("T5a", "acc-B", "dep-001",
                5000, 125, now.plusMillis(180L * DAY_MS), now);

        ScoreResult result = run(event, store, PostureView.empty(now.toEpochMilli()));
        EngineVerdict v = result.engineVerdict();

        assertEquals(Verdict.CONFIRM, v.decision(), "T5a TERM_DEPOSIT_BREAK must be CONFIRM, never HOLD/BLOCK");
        assertNotNull(result.explanation().customer(), "T5a must have a customer explanation");
        assertTrue(result.explanation().customer().toLowerCase().contains("purpose"),
                "T5a customer message must prompt for purpose");
    }

    @Test
    void t5b_liquidationKillChain_isHoldWithTypology() {
        String newPayee = "CY99BRANDNEW000";
        // Posture: €4875 freed in last 72h (principal €5000 − penalty €125), break moments ago.
        PostureView posture = new PostureView(487_500L, 0L, 0L, now.toEpochMilli() - 60_000L);
        FakeFeatureStore store = new FakeFeatureStore()
                .seedStats("acc-B", new AccountStatsView(0, 0, 0, 0, 0, false, false, List.of()));
        // Brand-new payee created 4 minutes ago, in the same session → B1, B2~1, B3.
        Counterparty cp = new Counterparty(com.cy.diakritis.common.dto.Addressing.IBAN,
                newPayee, newPayee, null, "New Payee", now.minusSeconds(240));
        ActionEvent event = Events.transfer("T5b", "acc-B", cp, 4850, 4980, Rail.SEPA, now);

        ScoreResult result = run(event, store, posture);
        EngineVerdict v = result.engineVerdict();

        assertEquals(Verdict.HOLD, v.decision(), "T5b must HOLD");
        assertTrue(v.typologies().contains(Typologies.LIQUIDATION_KILL_CHAIN),
                "T5b must name liquidation_kill_chain but was " + v.typologies());
        assertTrue(signalValue(v, "K1") > 0.6, "T5b K1 must be > 0.6 but was " + signalValue(v, "K1"));
        assertTrue(signalValue(v, "A2") > 0.6, "T5b A2 must be > 0.6 but was " + signalValue(v, "A2"));
        assertEquals(ReasonCodes.KILLCHAIN, result.reasonCode());
    }

    @Test
    void t6_romanceRepeatVictim_isHoldWithTypology() {
        String cpKey = "CY33ROMANCE0000";
        long firstSeen = now.toEpochMilli() - 14L * DAY_MS;
        List<RecentPayment> rising = List.of(
                new RecentPayment(20_000L, now.toEpochMilli() - 14L * DAY_MS),
                new RecentPayment(40_000L, now.toEpochMilli() - 11L * DAY_MS),
                new RecentPayment(70_000L, now.toEpochMilli() - 6L * DAY_MS),
                new RecentPayment(120_000L, now.toEpochMilli() - 2L * DAY_MS));
        FakeFeatureStore store = new FakeFeatureStore()
                .seedStats("acc-C", new AccountStatsView(50_000, 20_000, 50_000, 20_000, 30, false, false, List.of()))
                .seedBaseline("acc-C", cpKey, 4L, 62_500L, firstSeen, rising);
        Counterparty cp = new Counterparty(com.cy.diakritis.common.dto.Addressing.IBAN,
                cpKey, cpKey, null, "Romance Payee", null);
        ActionEvent event = Events.transfer("T6", "acc-C", cp, 2000, 9000, Rail.SEPA, now);

        ScoreResult result = run(event, store, PostureView.empty(now.toEpochMilli()));
        EngineVerdict v = result.engineVerdict();

        assertEquals(Verdict.HOLD, v.decision(), "T6 must HOLD");
        assertTrue(v.typologies().contains(Typologies.ROMANCE_REPEAT_VICTIM),
                "T6 must name romance_repeat_victim but was " + v.typologies());
        assertTrue(signalValue(v, "V2") > 0, "T6 V2 must be > 0");
        assertTrue(signalValue(v, "B2") > 0.4, "T6 B2 must be > 0.4 but was " + signalValue(v, "B2"));
    }

    private static double signalValue(EngineVerdict verdict, String id) {
        return verdict.signals().stream()
                .filter(s -> s.id().equals(id))
                .mapToDouble(com.cy.diakritis.common.dto.Signal::value)
                .findFirst()
                .orElse(0.0);
    }
}
