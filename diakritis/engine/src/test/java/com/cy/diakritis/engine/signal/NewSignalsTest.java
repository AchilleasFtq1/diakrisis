package com.cy.diakritis.engine.signal;

import com.cy.diakritis.common.dto.ActionEvent;
import com.cy.diakritis.common.dto.Addressing;
import com.cy.diakritis.common.dto.Counterparty;
import com.cy.diakritis.common.dto.Platform;
import com.cy.diakritis.common.dto.Rail;
import com.cy.diakritis.common.dto.SessionContext;
import com.cy.diakritis.common.persistence.RecentPayment;
import com.cy.diakritis.engine.Events;
import com.cy.diakritis.engine.FakeFeatureStore;
import com.cy.diakritis.engine.FakeObservations;
import com.cy.diakritis.engine.FakeReputation;
import com.cy.diakritis.engine.band.Weights;
import com.cy.diakritis.engine.store.AccountStatsView;
import com.cy.diakritis.engine.store.CidrGeoResolver;
import com.cy.diakritis.engine.store.GeoResolver;
import com.cy.diakritis.engine.store.ObservationsView;
import com.cy.diakritis.engine.store.PostureView;
import com.cy.diakritis.engine.store.ReputationView;
import com.cy.diakritis.engine.store.RuntimeState;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Per-signal fixtures for the Wave-5 signals (A4, V1, C1, C3, G1, G2, D1, D2, K2, K3, P1, MP1, MP2,
 * MP4, X1) built from hand-constructed {@link SignalContext}s. Each test asserts the signal fires
 * (≈1 / above threshold) under its specified condition and stays silent (0) otherwise.
 */
class NewSignalsTest {

    private static final long DAY_MS = 24L * 60L * 60L * 1000L;
    private static final long HOUR_MS = 60L * 60L * 1000L;
    private final Instant now = Instant.parse("2026-06-13T12:00:00Z");

    // --- context builders --------------------------------------------------------------------

    private SignalContext ctx(ActionEvent event, FakeFeatureStore store, RuntimeState runtime,
                              PostureView posture, ObservationsView obs, GeoResolver geo,
                              ReputationView reputation, long amountCents, long availableCents) {
        Counterparty cp = switch (event.payload()) {
            case com.cy.diakritis.common.dto.TransferPayload t -> t.counterparty();
            default -> null;
        };
        String cpKey = cp != null ? Identity.counterpartyKey(cp) : "NONE";
        return new SignalContext(event, store, runtime, posture, obs, geo, reputation, cpKey,
                amountCents, amountCents, availableCents, now);
    }

    // --- A4 threshold hugging ----------------------------------------------------------------

    @Test
    void a4FiresJustBelowRoundThreshold() {
        A4ThresholdHugging signal = new A4ThresholdHugging();
        // €990 hugs the €1,000 boundary (within 5%).
        ActionEvent event = Events.transfer("a4", "acc", Events.payee("CY1", null, null),
                990, 5000, Rail.SEPA, now);
        SignalContext c = ctx(event, new FakeFeatureStore(), new RuntimeState(),
                PostureView.empty(now.toEpochMilli()), ObservationsView.empty(),
                GeoResolver.unknownAll(), ReputationView.empty(), 99_000L, 500_000L);
        assertTrue(signal.value(c) > 0.5, "A4 should hug €1,000 strongly");

        // €500 is comfortably below any boundary band → 0.
        SignalContext clear = ctx(event, new FakeFeatureStore(), new RuntimeState(),
                PostureView.empty(now.toEpochMilli()), ObservationsView.empty(),
                GeoResolver.unknownAll(), ReputationView.empty(), 50_000L, 500_000L);
        assertEquals(0.0, signal.value(clear), 1e-9, "A4 silent well below thresholds");
    }

    // --- V1 burst velocity -------------------------------------------------------------------

    @Test
    void v1RisesWithRapidActions() {
        V1BurstVelocity signal = new V1BurstVelocity();
        RuntimeState runtime = new RuntimeState();
        long base = now.toEpochMilli();
        // Six actions within a few minutes → well above one/hour.
        for (int i = 0; i < 6; i++) {
            runtime.record("evt-v1-" + i, "acc", "CP" + i, 10_000L, base + i * 1000L);
        }
        ActionEvent event = Events.transfer("v1", "acc", Events.payee("CYV", null, null),
                100, 5000, Rail.SEPA, now);
        SignalContext c = ctx(event, new FakeFeatureStore(), runtime,
                PostureView.empty(base), ObservationsView.empty(),
                GeoResolver.unknownAll(), ReputationView.empty(), 10_000L, 500_000L);
        assertTrue(signal.value(c) > 0.5, "V1 should register a burst of rapid actions");

        // A single action over a fresh runtime → not a burst.
        RuntimeState calm = new RuntimeState();
        calm.record("evt-v1-calm", "acc", "CYV", 10_000L, base);
        SignalContext quiet = ctx(event, new FakeFeatureStore(), calm,
                PostureView.empty(base), ObservationsView.empty(),
                GeoResolver.unknownAll(), ReputationView.empty(), 10_000L, 500_000L);
        assertEquals(0.0, signal.value(quiet), 1e-9, "V1 silent for a single action");
    }

    // --- C1 out-of-pattern time --------------------------------------------------------------

    @Test
    void c1FiresOnNeverUsedWeekday() {
        C1OutOfPatternTime signal = new C1OutOfPatternTime();
        // 2026-06-13 is a Saturday (DOW 6), hour 12. Seed a weekday/hour baseline that excludes them.
        FakeObservations obs = new FakeObservations()
                .seen("acc", "DOW", "1", now.toEpochMilli() - 10 * DAY_MS, now.toEpochMilli() - DAY_MS)
                .seen("acc", "HOUR", "9", now.toEpochMilli() - 10 * DAY_MS, now.toEpochMilli() - DAY_MS);
        ActionEvent event = Events.transfer("c1", "acc", Events.payee("CYC", null, null),
                100, 5000, Rail.SEPA, now);
        SignalContext c = ctx(event, new FakeFeatureStore(), new RuntimeState(),
                PostureView.empty(now.toEpochMilli()), obs,
                GeoResolver.unknownAll(), ReputationView.empty(), 10_000L, 500_000L);
        assertTrue(signal.value(c) > 0.0, "C1 should fire when weekday and hour are out of pattern");

        // No baseline at all → cold start → 0.
        SignalContext cold = ctx(event, new FakeFeatureStore(), new RuntimeState(),
                PostureView.empty(now.toEpochMilli()), ObservationsView.empty(),
                GeoResolver.unknownAll(), ReputationView.empty(), 10_000L, 500_000L);
        assertEquals(0.0, signal.value(cold), 1e-9, "C1 silent on cold start");
    }

    // --- C3 retry pressure -------------------------------------------------------------------

    @Test
    void c3FiresOnRaisedRetries() {
        C3RetryPressure signal = new C3RetryPressure();
        RuntimeState runtime = new RuntimeState();
        String sessionId = "sess-c3";
        runtime.recordRaisedAttempt(sessionId, 10_000L, now.toEpochMilli());
        runtime.recordRaisedAttempt(sessionId, 20_000L, now.toEpochMilli() + 1000L);
        runtime.recordRaisedAttempt(sessionId, 30_000L, now.toEpochMilli() + 2000L);
        ActionEvent event = Events.transferInSession("c3", "acc", Events.payee("CY3", null, null),
                300, 5000, Rail.SEPA, sessionId, now);
        SignalContext c = ctx(event, new FakeFeatureStore(), runtime,
                PostureView.empty(now.toEpochMilli()), ObservationsView.empty(),
                GeoResolver.unknownAll(), ReputationView.empty(), 30_000L, 500_000L);
        assertTrue(signal.value(c) > 0.0, "C3 should fire on two raised retries");

        // No retries on a fresh session → 0.
        SignalContext fresh = ctx(
                Events.transferInSession("c3b", "acc", Events.payee("CY3", null, null),
                        300, 5000, Rail.SEPA, "other-sess", now),
                new FakeFeatureStore(), runtime, PostureView.empty(now.toEpochMilli()),
                ObservationsView.empty(), GeoResolver.unknownAll(), ReputationView.empty(),
                30_000L, 500_000L);
        assertEquals(0.0, signal.value(fresh), 1e-9, "C3 silent without raised retries");
    }

    // --- G1 unfamiliar geo / G2 new network --------------------------------------------------

    @Test
    void g1FiresOnUnfamiliarCountry() {
        G1UnfamiliarGeo signal = new G1UnfamiliarGeo();
        GeoResolver geo = CidrGeoResolver.of(Map.of(
                "203.0.113.0/24", "CY",
                "198.51.100.0/24", "RU"));
        // Account familiar only with CY; an RU IP is unfamiliar.
        FakeObservations obs = new FakeObservations()
                .seen("acc", "GEO", "CY", now.toEpochMilli() - 30 * DAY_MS, now.toEpochMilli() - DAY_MS);
        SessionContext fromRu = Events.session("s", now, "198.51.100.7", "dev", Platform.IOS);
        ActionEvent event = Events.transferWithSession("g1", "acc",
                Events.payee("CYG", null, null), 100, 5000, Rail.SEPA, fromRu);
        SignalContext c = ctx(event, new FakeFeatureStore(), new RuntimeState(),
                PostureView.empty(now.toEpochMilli()), obs, geo, ReputationView.empty(),
                10_000L, 500_000L);
        assertEquals(1.0, signal.value(c), 1e-9, "G1 fires for an unfamiliar country");

        // Same account, familiar CY IP → 0.
        SessionContext fromCy = Events.session("s", now, "203.0.113.7", "dev", Platform.IOS);
        ActionEvent cyEvent = Events.transferWithSession("g1b", "acc",
                Events.payee("CYG", null, null), 100, 5000, Rail.SEPA, fromCy);
        SignalContext familiar = ctx(cyEvent, new FakeFeatureStore(), new RuntimeState(),
                PostureView.empty(now.toEpochMilli()), obs, geo, ReputationView.empty(),
                10_000L, 500_000L);
        assertEquals(0.0, signal.value(familiar), 1e-9, "G1 silent for a familiar country");
    }

    @Test
    void g2FiresOnNewPrefixSameCountry() {
        G2NewNetwork signal = new G2NewNetwork();
        GeoResolver geo = CidrGeoResolver.of(Map.of("203.0.0.0/8", "CY"));
        // Familiar country CY and familiar prefix 203.0.113; a 203.0.200.* prefix is new.
        FakeObservations obs = new FakeObservations()
                .seen("acc", "GEO", "CY", now.toEpochMilli() - 30 * DAY_MS, now.toEpochMilli() - DAY_MS)
                .seen("acc", "NETWORK", "203.0.113", now.toEpochMilli() - 30 * DAY_MS, now.toEpochMilli() - DAY_MS);
        SessionContext newPrefix = Events.session("s", now, "203.0.200.9", "dev", Platform.IOS);
        ActionEvent event = Events.transferWithSession("g2", "acc",
                Events.payee("CYN", null, null), 100, 5000, Rail.SEPA, newPrefix);
        SignalContext c = ctx(event, new FakeFeatureStore(), new RuntimeState(),
                PostureView.empty(now.toEpochMilli()), obs, geo, ReputationView.empty(),
                10_000L, 500_000L);
        assertEquals(1.0, signal.value(c), 1e-9, "G2 fires for a new prefix in a familiar country");

        // Familiar prefix → 0.
        SessionContext known = Events.session("s", now, "203.0.113.50", "dev", Platform.IOS);
        ActionEvent knownEvent = Events.transferWithSession("g2b", "acc",
                Events.payee("CYN", null, null), 100, 5000, Rail.SEPA, known);
        SignalContext familiar = ctx(knownEvent, new FakeFeatureStore(), new RuntimeState(),
                PostureView.empty(now.toEpochMilli()), obs, geo, ReputationView.empty(),
                10_000L, 500_000L);
        assertEquals(0.0, signal.value(familiar), 1e-9, "G2 silent for a familiar prefix");
    }

    // --- D1 device-age decay / D2 platform anomaly -------------------------------------------

    @Test
    void d1HighForFreshDeviceDecaysWithAge() {
        D1DeviceAgeDecay signal = new D1DeviceAgeDecay();
        // Account has a device baseline; the current device was first seen moments ago → ~1.0.
        FakeObservations fresh = new FakeObservations()
                .seen("acc", "DEVICE", "dev-known", now.toEpochMilli() - 60 * DAY_MS, now.toEpochMilli() - DAY_MS)
                .seen("acc", "DEVICE", "dev-new", now.toEpochMilli() - 60_000L, now.toEpochMilli());
        SessionContext s = Events.session("s", now, "203.0.113.7", "dev-new", Platform.IOS);
        ActionEvent event = Events.transferWithSession("d1", "acc",
                Events.payee("CYD", null, null), 100, 5000, Rail.SEPA, s);
        SignalContext c = ctx(event, new FakeFeatureStore(), new RuntimeState(),
                PostureView.empty(now.toEpochMilli()), fresh, GeoResolver.unknownAll(),
                ReputationView.empty(), 10_000L, 500_000L);
        assertTrue(signal.value(c) > 0.9, "D1 ~1.0 for a device seen moments ago");

        // An old device (90 days) decays to near 0.
        FakeObservations old = new FakeObservations()
                .seen("acc", "DEVICE", "dev-old", now.toEpochMilli() - 90 * DAY_MS, now.toEpochMilli() - DAY_MS);
        SessionContext sOld = Events.session("s", now, "203.0.113.7", "dev-old", Platform.IOS);
        ActionEvent oldEvent = Events.transferWithSession("d1b", "acc",
                Events.payee("CYD", null, null), 100, 5000, Rail.SEPA, sOld);
        SignalContext cOld = ctx(oldEvent, new FakeFeatureStore(), new RuntimeState(),
                PostureView.empty(now.toEpochMilli()), old, GeoResolver.unknownAll(),
                ReputationView.empty(), 10_000L, 500_000L);
        assertTrue(signal.value(cOld) < 0.1, "D1 ~0 for a long-established device");
    }

    @Test
    void d1SilentOnColdStart() {
        D1DeviceAgeDecay signal = new D1DeviceAgeDecay();
        SessionContext s = Events.session("s", now, "203.0.113.7", "dev-new", Platform.IOS);
        ActionEvent event = Events.transferWithSession("d1c", "acc",
                Events.payee("CYD", null, null), 100, 5000, Rail.SEPA, s);
        SignalContext c = ctx(event, new FakeFeatureStore(), new RuntimeState(),
                PostureView.empty(now.toEpochMilli()), ObservationsView.empty(),
                GeoResolver.unknownAll(), ReputationView.empty(), 10_000L, 500_000L);
        assertEquals(0.0, signal.value(c), 1e-9, "D1 silent when there is no device baseline");
    }

    @Test
    void d2FiresOnPlatformSwitch() {
        D2PlatformAnomaly signal = new D2PlatformAnomaly();
        // Android-only account suddenly arrives on WEB.
        FakeObservations obs = new FakeObservations()
                .seen("acc", "PLATFORM", "ANDROID", now.toEpochMilli() - 30 * DAY_MS, now.toEpochMilli() - DAY_MS);
        SessionContext web = Events.session("s", now, "203.0.113.7", "dev", Platform.WEB);
        ActionEvent event = Events.transferWithSession("d2", "acc",
                Events.payee("CYP", null, null), 100, 5000, Rail.SEPA, web);
        SignalContext c = ctx(event, new FakeFeatureStore(), new RuntimeState(),
                PostureView.empty(now.toEpochMilli()), obs, GeoResolver.unknownAll(),
                ReputationView.empty(), 10_000L, 500_000L);
        assertEquals(1.0, signal.value(c), 1e-9, "D2 fires when an Android-only account uses WEB");

        // Same platform as history → 0.
        SessionContext android = Events.session("s", now, "203.0.113.7", "dev", Platform.ANDROID);
        ActionEvent same = Events.transferWithSession("d2b", "acc",
                Events.payee("CYP", null, null), 100, 5000, Rail.SEPA, android);
        SignalContext familiar = ctx(same, new FakeFeatureStore(), new RuntimeState(),
                PostureView.empty(now.toEpochMilli()), obs, GeoResolver.unknownAll(),
                ReputationView.empty(), 10_000L, 500_000L);
        assertEquals(0.0, signal.value(familiar), 1e-9, "D2 silent on a familiar platform");
    }

    // --- K2 limit raised / K3 add burst ------------------------------------------------------

    @Test
    void k2FiresWhenRaisedLimitCoversAmount() {
        K2LimitRaisedRecently signal = new K2LimitRaisedRecently();
        // €5,000 of fresh headroom on posture; paying €5,000 → fully covered.
        PostureView posture = new PostureView(0L, 500_000L, 0L, now.toEpochMilli());
        ActionEvent event = Events.transfer("k2", "acc", Events.payee("CYK", null, null),
                5000, 9000, Rail.SEPA, now);
        SignalContext c = ctx(event, new FakeFeatureStore(), new RuntimeState(),
                posture, ObservationsView.empty(), GeoResolver.unknownAll(),
                ReputationView.empty(), 500_000L, 900_000L);
        assertTrue(signal.value(c) >= 0.99, "K2 saturates when the raise fully covers the amount");

        // No recent raise → 0.
        SignalContext none = ctx(event, new FakeFeatureStore(), new RuntimeState(),
                PostureView.empty(now.toEpochMilli()), ObservationsView.empty(),
                GeoResolver.unknownAll(), ReputationView.empty(), 500_000L, 900_000L);
        assertEquals(0.0, signal.value(none), 1e-9, "K2 silent without a recent raise");
    }

    @Test
    void k3FiresOnBeneficiaryAddBurst() {
        K3BeneficiaryAddBurst signal = new K3BeneficiaryAddBurst();
        PostureView burst = new PostureView(0L, 0L, 3L, now.toEpochMilli());
        ActionEvent event = Events.transfer("k3", "acc", Events.payee("CYB", null, null),
                100, 5000, Rail.SEPA, now);
        SignalContext c = ctx(event, new FakeFeatureStore(), new RuntimeState(),
                burst, ObservationsView.empty(), GeoResolver.unknownAll(),
                ReputationView.empty(), 10_000L, 500_000L);
        assertTrue(signal.value(c) > 0.5, "K3 fires on three adds in the window");

        // A single add is below the minimum → 0.
        PostureView one = new PostureView(0L, 0L, 1L, now.toEpochMilli());
        SignalContext single = ctx(event, new FakeFeatureStore(), new RuntimeState(),
                one, ObservationsView.empty(), GeoResolver.unknownAll(),
                ReputationView.empty(), 10_000L, 500_000L);
        assertEquals(0.0, signal.value(single), 1e-9, "K3 silent on a single add");
    }

    // --- P1 alias re-point -------------------------------------------------------------------

    @Test
    void p1FiresWhenAliasResolvesToNewAccount() {
        P1AliasRepoint signal = new P1AliasRepoint();
        String alias = "+35799123456";
        // History: the alias used to resolve to ACC-OLD; now it resolves to ACC-NEW → SIM-swap tell.
        FakeObservations obs = new FakeObservations().aliasResolvedTo("acc", alias, "ACC-OLD");
        ActionEvent event = Events.p2pAlias("p1", "acc", Addressing.MSISDN, alias, "ACC-NEW",
                500, 5000, now);
        SignalContext c = ctx(event, new FakeFeatureStore(), new RuntimeState(),
                PostureView.empty(now.toEpochMilli()), obs, GeoResolver.unknownAll(),
                ReputationView.empty(), 50_000L, 500_000L);
        assertEquals(1.0, signal.value(c), 1e-9, "P1 fires when the alias re-points to a new account");

        // Same resolution as history → 0.
        FakeObservations same = new FakeObservations().aliasResolvedTo("acc", alias, "ACC-NEW");
        SignalContext unchanged = ctx(event, new FakeFeatureStore(), new RuntimeState(),
                PostureView.empty(now.toEpochMilli()), same, GeoResolver.unknownAll(),
                ReputationView.empty(), 50_000L, 500_000L);
        assertEquals(0.0, signal.value(unchanged), 1e-9, "P1 silent when resolution is unchanged");

        // First-ever resolution (no history) → 0 (B1's job, not a re-point).
        SignalContext firstTime = ctx(event, new FakeFeatureStore(), new RuntimeState(),
                PostureView.empty(now.toEpochMilli()), ObservationsView.empty(),
                GeoResolver.unknownAll(), ReputationView.empty(), 50_000L, 500_000L);
        assertEquals(0.0, signal.value(firstTime), 1e-9, "P1 silent on a first-ever alias resolution");
    }

    // --- X1 cross-account reputation ---------------------------------------------------------

    @Test
    void x1FiresOnRecentlyFlaggedCounterpartyAndDecays() {
        X1CrossAccountReputation signal = new X1CrossAccountReputation();
        String cpKey = "IBAN|MULE-1";
        ActionEvent event = Events.transfer("x1", "acc",
                new Counterparty(Addressing.IBAN, cpKey, cpKey, null, "Mule", null),
                4000, 9000, Rail.SEPA, now);

        // Flagged 30 seconds ago with a BLOCK → ~full strength.
        FakeReputation fresh = new FakeReputation().flag(cpKey, now.toEpochMilli() - 30_000L, "BLOCK");
        SignalContext recent = ctx(event, new FakeFeatureStore(), new RuntimeState(),
                PostureView.empty(now.toEpochMilli()), ObservationsView.empty(),
                GeoResolver.unknownAll(), fresh, 400_000L, 900_000L);
        assertTrue(signal.value(recent) > 0.9, "X1 ~1.0 for a flag seconds ago");

        // Flagged one half-life (6h) ago → ~0.5.
        FakeReputation halfLife = new FakeReputation()
                .flag(cpKey, now.toEpochMilli() - Weights.X1_HALFLIFE_HOURS * HOUR_MS, "BLOCK");
        SignalContext decayed = ctx(event, new FakeFeatureStore(), new RuntimeState(),
                PostureView.empty(now.toEpochMilli()), ObservationsView.empty(),
                GeoResolver.unknownAll(), halfLife, 400_000L, 900_000L);
        assertEquals(0.5, signal.value(decayed), 0.05, "X1 ~0.5 one half-life after the flag");

        // Never flagged → 0.
        SignalContext clean = ctx(event, new FakeFeatureStore(), new RuntimeState(),
                PostureView.empty(now.toEpochMilli()), ObservationsView.empty(),
                GeoResolver.unknownAll(), ReputationView.empty(), 400_000L, 900_000L);
        assertEquals(0.0, signal.value(clean), 1e-9, "X1 silent for a clean counterparty");
    }

    // --- MP1 / MP2 / MP4 batch signals -------------------------------------------------------

    @Test
    void mp1SharesNewCounterpartiesAcrossBatch() {
        MP1NewCounterpartyShare signal = new MP1NewCounterpartyShare();
        // Three lines, none with history → share 1.0.
        ActionEvent allNew = Events.massPayment("mp1", "acc", "B1", List.of(
                Events.line("L1", Events.payee("CY1", null, null), 100),
                Events.line("L2", Events.payee("CY2", null, null), 100),
                Events.line("L3", Events.payee("CY3", null, null), 100)),
                300, 9000, Rail.SEPA, now);
        SignalContext c = ctx(allNew, new FakeFeatureStore(), new RuntimeState(),
                PostureView.empty(now.toEpochMilli()), ObservationsView.empty(),
                GeoResolver.unknownAll(), ReputationView.empty(), 30_000L, 900_000L);
        assertEquals(1.0, signal.value(c), 1e-9, "MP1 = 1.0 when every line is a new counterparty");

        // All lines have history → share 0.
        FakeFeatureStore store = new FakeFeatureStore()
                .seedBaseline("acc", "CY1", 5L, 10_000L, now.toEpochMilli() - 30 * DAY_MS, List.of())
                .seedBaseline("acc", "CY2", 5L, 10_000L, now.toEpochMilli() - 30 * DAY_MS, List.of())
                .seedBaseline("acc", "CY3", 5L, 10_000L, now.toEpochMilli() - 30 * DAY_MS, List.of());
        SignalContext payroll = ctx(allNew, store, new RuntimeState(),
                PostureView.empty(now.toEpochMilli()), ObservationsView.empty(),
                GeoResolver.unknownAll(), ReputationView.empty(), 30_000L, 900_000L);
        assertEquals(0.0, signal.value(payroll), 1e-9, "MP1 = 0 when every line is an established payee");
    }

    @Test
    void mp2FiresWhenBatchTotalIsAnomalous() {
        MP2CadenceTotalAnomaly signal = new MP2CadenceTotalAnomaly();
        // Account's outgoing baseline is tight around €100; a €30,000 batch total is many sigma out.
        FakeFeatureStore store = new FakeFeatureStore().seedStats("acc",
                new AccountStatsView(10_000L, 1_000L, 10_000L, 1_000L, 200L, true, false, List.of()));
        ActionEvent batch = Events.massPayment("mp2", "acc", "B2", List.of(
                Events.line("L1", Events.payee("CY1", null, null), 30000)),
                30000, 90000, Rail.SEPA, now);
        SignalContext c = ctx(batch, store, new RuntimeState(),
                PostureView.empty(now.toEpochMilli()), ObservationsView.empty(),
                GeoResolver.unknownAll(), ReputationView.empty(), 3_000_000L, 9_000_000L);
        assertTrue(signal.value(c) > 0.5, "MP2 fires for an outsized batch total");

        // No outgoing baseline → 0.
        SignalContext cold = ctx(batch, new FakeFeatureStore(), new RuntimeState(),
                PostureView.empty(now.toEpochMilli()), ObservationsView.empty(),
                GeoResolver.unknownAll(), ReputationView.empty(), 3_000_000L, 9_000_000L);
        assertEquals(0.0, signal.value(cold), 1e-9, "MP2 silent without an outgoing baseline");
    }

    @Test
    void mp4FiresWhenBatchDrainsBalance() {
        MP4BatchDrain signal = new MP4BatchDrain();
        // Batch total €9,500 of a €10,000 balance → 95% drain.
        ActionEvent batch = Events.massPayment("mp4", "acc", "B4", List.of(
                Events.line("L1", Events.payee("CY1", null, null), 9500)),
                9500, 10000, Rail.SEPA, now);
        SignalContext c = ctx(batch, new FakeFeatureStore(), new RuntimeState(),
                PostureView.empty(now.toEpochMilli()), ObservationsView.empty(),
                GeoResolver.unknownAll(), ReputationView.empty(), 950_000L, 1_000_000L);
        assertTrue(signal.value(c) > 0.8, "MP4 fires when the batch drains the balance");

        // A small batch of a large balance → 0.
        ActionEvent small = Events.massPayment("mp4b", "acc", "B5", List.of(
                Events.line("L1", Events.payee("CY1", null, null), 100)),
                100, 100000, Rail.SEPA, now);
        SignalContext light = ctx(small, new FakeFeatureStore(), new RuntimeState(),
                PostureView.empty(now.toEpochMilli()), ObservationsView.empty(),
                GeoResolver.unknownAll(), ReputationView.empty(), 10_000L, 10_000_000L);
        assertEquals(0.0, signal.value(light), 1e-9, "MP4 silent for a light batch");
    }
}
