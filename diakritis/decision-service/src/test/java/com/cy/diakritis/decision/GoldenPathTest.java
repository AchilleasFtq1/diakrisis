package com.cy.diakritis.decision;

import com.cy.diakritis.common.dto.Decision;
import com.cy.diakritis.common.persistence.AccountPostureItem;
import com.cy.diakritis.common.persistence.AccountStatsItem;
import com.cy.diakritis.common.persistence.CounterpartyBaselineItem;
import com.cy.diakritis.common.persistence.CounterpartyByNameItem;
import com.cy.diakritis.common.persistence.RecentPayment;
import com.cy.diakritis.common.persistence.Tables;
import com.cy.diakritis.common.security.JwtService;
import com.cy.diakritis.common.security.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.client.RestClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Golden-path end-to-end suite (T1-T6 + assertable contract invariants) against a real DynamoDB
 * Local on {@code :8000}. The Spring context boots the full decision pipeline (engine + the real M1
 * model + the Dynamo-backed feature store + the idempotent commit path) on a random port; tests
 * drive it over HTTP exactly as a caller would.
 *
 * <p><strong>Isolation.</strong> Every scenario seeds its own feature rows under a freshly minted
 * account id ({@code gp-A-…}/{@code gp-B-…}/{@code gp-C-…}) in {@link BeforeEach}. A unique account
 * id per test isolates two shared pieces of state at once: the in-memory table set (disjoint from
 * the ETL demo seed acc-A/B/C and from sibling tests) and the process-wide {@code RuntimeState}
 * rolling 24h window, which is keyed by {@code (accountId, cpKey)} — so no test's payment bleeds
 * into another's logical-amount sum. Each request also carries a unique event id.
 *
 * <p><strong>Clock.</strong> The decision service stamps {@code Instant.now()} internally, so the
 * suite "freezes" the clock by anchoring every seeded timestamp to a single {@link #now} captured in
 * {@link BeforeEach} and asserting age/recency-relative outcomes. The T5 ordering invariant (deposit
 * break before the liquidation sweep) is honoured by running T5a before T5b and seeding the freed
 * funds posture the break commits.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "diakrisis.jwt.secret=diakrisis-golden-path-test-secret-key-0123456789",
        "diakrisis.dynamo.endpoint=http://localhost:8000",
        "diakrisis.dynamo.region=us-east-1",
        "diakrisis.dynamo.auto-create=true",
        "diakrisis.models-dir=/Users/achilleaseftychiou/Documents/Projects/diakrisis/diakrisis-models"
})
class GoldenPathTest {

    private static final long DAY_MS = 24L * 60L * 60L * 1000L;
    private static final AtomicLong SEQUENCE = new AtomicLong();

    // A per-run namespace so account ids never collide with rows left by a prior run in the shared,
    // long-lived in-memory DynamoDB (which is not reset between JVM launches). Without this a reused
    // id like "gp-A-1" could read a previous run's stale baseline for a counterparty this run does
    // not re-seed, perturbing a boundary-sensitive score.
    private static final String RUN = Long.toString(System.nanoTime(), 36);

    // Frozen action timestamp (the contract's "freeze the clock"). The M1 model derives its
    // hour/day-of-week cyclic features from context.ts, so pinning it makes the M1 contribution
    // deterministic; the age/recency signals key off the service's Instant.now() and the seeded
    // epochs (anchored to {@link #now}), which remain correct relative to wall-clock.
    private static final String FROZEN_TS = "2026-06-13T12:00:00Z";

    // Real Berka counterparty keys used by the disclosed scenarios.
    private static final String CD_CP = "CD|46939146";
    private static final String KL_CP = "KL|64831554";

    @LocalServerPort
    private int port;

    @Autowired
    private DynamoDbEnhancedClient enhanced;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private JsonMapper jsonMapper;

    private DynamoDbTable<CounterpartyBaselineItem> baselineTable;
    private DynamoDbTable<AccountStatsItem> statsTable;
    private DynamoDbTable<CounterpartyByNameItem> byNameTable;
    private DynamoDbTable<AccountPostureItem> postureTable;

    private RestClient http;
    private Instant now;

    @BeforeEach
    void setUp() {
        this.now = Instant.now();
        this.baselineTable = enhanced.table(Tables.COUNTERPARTY_BASELINE,
                TableSchema.fromBean(CounterpartyBaselineItem.class));
        this.statsTable = enhanced.table(Tables.ACCOUNT_STATS,
                TableSchema.fromBean(AccountStatsItem.class));
        this.byNameTable = enhanced.table(Tables.COUNTERPARTY_BY_NAME,
                TableSchema.fromBean(CounterpartyByNameItem.class));
        this.postureTable = enhanced.table(Tables.ACCOUNT_POSTURE,
                TableSchema.fromBean(AccountPostureItem.class));

        // Use the application's snake_case Jackson mapper so the response Decision (snake_case wire
        // format, e.g. latency_ms) round-trips correctly into the DTO.
        JacksonJsonHttpMessageConverter converter = new JacksonJsonHttpMessageConverter(jsonMapper);
        this.http = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .messageConverters(converters -> {
                    converters.removeIf(c -> c instanceof JacksonJsonHttpMessageConverter);
                    converters.add(converter);
                })
                .build();
    }

    // ---------------------------------------------------------------------------------------------
    // T1-T6
    // ---------------------------------------------------------------------------------------------

    @Test
    void t1_routinePaymentToEstablishedPayee_isAllowAndScaExempt() {
        String acct = freshAccountA();
        Decision d = post(transferBody(uniqueId("t1"), acct, CD_CP, "CD Supplier", null, 120, 4500),
                token("customer-A", Role.CUSTOMER, acct), HttpStatus.OK);

        assertEquals("ALLOW", d.engineVerdict().decision().name(), "T1 must ALLOW");
        assertTrue(d.engineVerdict().score() >= 0 && d.engineVerdict().score() <= 29,
                "T1 score must be 0-29 but was " + d.engineVerdict().score());
        assertTrue(d.engineVerdict().scaExempt(), "T1 must be SCA-exempt");
        assertTrue(d.engineVerdict().typologies().isEmpty(), "T1 must name no typology");
        assertNull(d.explanation().customer(), "T1 ALLOW must have null customer explanation");
        assertEquals(d.engineVerdict().decision(), d.combined().decision(), "T1 combined == engine");
        assertTrue(d.latencyMs() < 50, "T1 latencyMs must be < 50 but was " + d.latencyMs());
    }

    @Test
    void t2_secondEstablishedPayee_isAllowAndScaExempt() {
        String acct = freshAccountA();
        Decision d = post(transferBody(uniqueId("t2"), acct, KL_CP, "KL Supplier", null, 750, 3200),
                token("customer-A", Role.CUSTOMER, acct), HttpStatus.OK);

        assertEquals("ALLOW", d.engineVerdict().decision().name(), "T2 must ALLOW");
        assertTrue(d.engineVerdict().score() >= 0 && d.engineVerdict().score() <= 29,
                "T2 score must be 0-29 but was " + d.engineVerdict().score());
        assertTrue(d.engineVerdict().scaExempt(), "T2 must be SCA-exempt");
        assertNull(d.explanation().customer(), "T2 ALLOW must have null customer explanation");
        assertEquals(d.engineVerdict().decision(), d.combined().decision(), "T2 combined == engine");
    }

    @Test
    void t3_anomalousAmountToEstablishedPayee_isConfirm() {
        String acct = freshAccountA();
        Decision d = post(transferBody(uniqueId("t3"), acct, CD_CP, "CD Supplier", null, 700, 3200),
                token("customer-A", Role.CUSTOMER, acct), HttpStatus.OK);

        assertEquals("CONFIRM", d.engineVerdict().decision().name(), "T3 must CONFIRM");
        assertTrue(d.engineVerdict().score() >= 30 && d.engineVerdict().score() <= 59,
                "T3 score must be 30-59 but was " + d.engineVerdict().score());
        assertFalse(d.engineVerdict().scaExempt(), "T3 CONFIRM is not SCA-exempt");
        assertTrue(signal(d, "A3") > 0, "T3 A3 must be > 0 but was " + signal(d, "A3"));
        assertNotNull(d.explanation().customer(), "T3 CONFIRM must have a customer explanation");
        assertEquals(d.engineVerdict().decision(), d.combined().decision(), "T3 combined == engine");
    }

    @Test
    void t4_invoiceRedirection_isHoldWithTypologyAndB5() {
        String acct = freshAccountA();
        String newIban = "CY00NEWIBAN0000";
        Decision d = post(transferBody(uniqueId("t4"), acct, newIban, "Acme Supplies Ltd", null, 4200, 8000),
                token("customer-A", Role.CUSTOMER, acct), HttpStatus.OK);

        assertEquals("HOLD", d.engineVerdict().decision().name(), "T4 must HOLD");
        assertTrue(d.engineVerdict().typologies().contains("invoice_redirection"),
                "T4 must name invoice_redirection but was " + d.engineVerdict().typologies());
        assertEquals(1.0, signal(d, "B5"), 1e-9, "T4 B5 must be 1.0");
        assertNotNull(d.explanation().customer(), "T4 HOLD must have a customer explanation");
        assertEquals("DKR-INVOICE", d.reasonCode(), "T4 reason code must be DKR-INVOICE");
        assertEquals(d.engineVerdict().decision(), d.combined().decision(), "T4 combined == engine");
    }

    @Test
    void t5a_termDepositBreak_isConfirmNeverHold_andHasPurposePrompt() {
        String acct = freshAccountB();
        Decision d = post(depositBreakBody(uniqueId("t5a"), acct, "dep-001", 5000, 125),
                token("customer-B", Role.CUSTOMER, acct), HttpStatus.OK);

        assertEquals("CONFIRM", d.engineVerdict().decision().name(),
                "T5a TERM_DEPOSIT_BREAK must be CONFIRM, never HOLD/BLOCK");
        assertNotNull(d.explanation().customer(), "T5a must have a customer explanation");
        assertTrue(d.explanation().customer().toLowerCase().contains("purpose"),
                "T5a customer message must prompt for purpose");
        assertEquals(d.engineVerdict().decision(), d.combined().decision(), "T5a combined == engine");
    }

    @Test
    void t5b_liquidationKillChain_isHoldWithTypology() {
        String acct = freshAccountB();
        // T5a-before-T5b: the break commits the freed-funds posture that T5b's sweep trips. We both
        // run the break and seed the posture so the ordering invariant holds deterministically.
        post(depositBreakBody(uniqueId("t5a-pre"), acct, "dep-001", 5000, 125),
                token("customer-B", Role.CUSTOMER, acct), HttpStatus.OK);
        seedFreedFundsPosture(acct, 487_500L, now.minusSeconds(60));

        String newPayee = "CY99BRANDNEW000";
        Decision d = post(
                transferBody(uniqueId("t5b"), acct, newPayee, null, now.minusSeconds(240), 4850, 4980),
                token("customer-B", Role.CUSTOMER, acct), HttpStatus.OK);

        assertEquals("HOLD", d.engineVerdict().decision().name(),
                "T5b must HOLD (a single typology never reaches BLOCK) but was "
                        + d.engineVerdict().decision() + " score=" + d.engineVerdict().score());
        assertTrue(d.engineVerdict().typologies().contains("liquidation_kill_chain"),
                "T5b must name liquidation_kill_chain but was " + d.engineVerdict().typologies());
        assertTrue(signal(d, "K1") > 0.6, "T5b K1 must be > 0.6 but was " + signal(d, "K1"));
        assertTrue(signal(d, "A2") > 0.6, "T5b A2 must be > 0.6 but was " + signal(d, "A2"));
        assertNotNull(d.lifecycle().hold(), "T5b HOLD must carry hold info");
        assertEquals("DKR-KILLCHAIN", d.reasonCode(), "T5b reason code must be DKR-KILLCHAIN");
        assertEquals(d.engineVerdict().decision(), d.combined().decision(), "T5b combined == engine");
    }

    @Test
    void t6_romanceRepeatVictim_isHoldWithTypology() {
        String acct = freshAccountC();
        String cpKey = "CY33ROMANCE0000";
        Decision d = post(transferBody(uniqueId("t6"), acct, cpKey, "Romance Payee", null, 2000, 9000),
                token("customer-C", Role.CUSTOMER, acct), HttpStatus.OK);

        assertEquals("HOLD", d.engineVerdict().decision().name(), "T6 must HOLD");
        assertTrue(d.engineVerdict().typologies().contains("romance_repeat_victim"),
                "T6 must name romance_repeat_victim but was " + d.engineVerdict().typologies());
        assertTrue(signal(d, "V2") > 0, "T6 V2 must be > 0");
        assertTrue(signal(d, "B2") > 0.4, "T6 B2 must be > 0.4 but was " + signal(d, "B2"));
        assertEquals(d.engineVerdict().decision(), d.combined().decision(), "T6 combined == engine");
    }

    // ---------------------------------------------------------------------------------------------
    // Contract invariants (CI-1, CI-4, CI-7, CI-8, CI-9, CI-11)
    // ---------------------------------------------------------------------------------------------

    @Test
    void ci1_idempotentReplay_returnsIdenticalBodyWithNoDoubleMutation() {
        String acct = freshAccountB();
        String eventId = uniqueId("ci1");
        // A held kill-chain so the replay path also exercises the posture/reputation commit guard.
        post(depositBreakBody(uniqueId("ci1-break"), acct, "dep-001", 5000, 125),
                token("customer-B", Role.CUSTOMER, acct), HttpStatus.OK);
        seedFreedFundsPosture(acct, 487_500L, now.minusSeconds(60));

        String body = transferBody(eventId, acct, "CY99REPLAY00000", null, now.minusSeconds(240), 4850, 4980);
        String tok = token("customer-B", Role.CUSTOMER, acct);

        ResponseEntity<String> first = postRaw(body, tok);
        ResponseEntity<String> second = postRaw(body, tok);

        assertEquals(HttpStatus.OK, first.getStatusCode(), "CI-1 first decision must be 200");
        assertEquals(HttpStatus.OK, second.getStatusCode(), "CI-1 replay must be 200");
        assertEquals(first.getBody(), second.getBody(),
                "CI-1 idempotent replay must return an identical body (no re-scoring, no double mutation)");
    }

    @Test
    void ci4_combinedEqualsEngine_whenAiUnavailable() {
        String acct = freshAccountA();
        Decision d = post(transferBody(uniqueId("ci4"), acct, CD_CP, "CD Supplier", null, 120, 4500),
                token("customer-A", Role.CUSTOMER, acct), HttpStatus.OK);

        assertEquals("UNAVAILABLE", d.aiCoJudge().status(), "CI-4 AI co-judge must be UNAVAILABLE");
        assertEquals(d.engineVerdict().decision(), d.combined().decision(),
                "CI-4 combined must equal engine when the AI co-judge is unavailable");
    }

    @Test
    void ci7_explanationNullIffAllow() {
        String allowAcct = freshAccountA();
        Decision allow = post(transferBody(uniqueId("ci7a"), allowAcct, CD_CP, "CD Supplier", null, 120, 4500),
                token("customer-A", Role.CUSTOMER, allowAcct), HttpStatus.OK);
        String confirmAcct = freshAccountA();
        Decision confirm = post(transferBody(uniqueId("ci7b"), confirmAcct, CD_CP, "CD Supplier", null, 700, 3200),
                token("customer-A", Role.CUSTOMER, confirmAcct), HttpStatus.OK);

        assertEquals("ALLOW", allow.engineVerdict().decision().name());
        assertNull(allow.explanation().customer(), "CI-7 ALLOW must have a null customer explanation");
        assertFalse(confirm.engineVerdict().decision().name().equals("ALLOW"));
        assertNotNull(confirm.explanation().customer(),
                "CI-7 a non-ALLOW decision must carry a customer explanation");
    }

    @Test
    void ci8_malformedRequest_neverFiveHundred() {
        String acct = freshAccountA();
        String garbage = "{ this is not valid json )";
        ResponseEntity<String> resp = postRaw(garbage, token("customer-A", Role.CUSTOMER, acct));
        assertTrue(resp.getStatusCode() == HttpStatus.BAD_REQUEST
                        || resp.getStatusCode() == HttpStatus.UNPROCESSABLE_ENTITY,
                "CI-8 malformed body must be 400/422 but was " + resp.getStatusCode());

        // A well-formed envelope whose payload is missing for the event_type is a 4xx, never a 500.
        String semanticallyInvalid = """
                {"event_id":"ci8-x","account_id":"%s","event_type":"TRANSFER",
                 "context":{"ts":"%s","session_id":"s","channel":"WEB",
                 "device":{"device_id":"d","platform":"WEB"}}}
                """.formatted(acct, now);
        ResponseEntity<String> resp2 = postRaw(semanticallyInvalid, token("customer-A", Role.CUSTOMER, acct));
        assertTrue(resp2.getStatusCode().is4xxClientError(),
                "CI-8 semantically invalid body must be 4xx but was " + resp2.getStatusCode());
    }

    @Test
    void ci9_nonMonetaryCap_termDepositBreakNeverPastConfirm() {
        String acct = freshAccountB();
        Decision d = post(depositBreakBody(uniqueId("ci9"), acct, "dep-001", 5000, 125),
                token("customer-B", Role.CUSTOMER, acct), HttpStatus.OK);
        assertEquals("CONFIRM", d.engineVerdict().decision().name(),
                "CI-9 non-monetary cap: TERM_DEPOSIT_BREAK must be capped at CONFIRM");
    }

    @Test
    void ci11_latencyUnderFiftyMillis() {
        String acct = freshAccountA();
        Decision d = post(transferBody(uniqueId("ci11"), acct, CD_CP, "CD Supplier", null, 120, 4500),
                token("customer-A", Role.CUSTOMER, acct), HttpStatus.OK);
        assertTrue(d.latencyMs() < 50, "CI-11 latencyMs must be < 50 but was " + d.latencyMs());
    }

    // ---------------------------------------------------------------------------------------------
    // Per-test account seeding (a fresh account id isolates Dynamo rows and the rolling window)
    // ---------------------------------------------------------------------------------------------

    /** A 7819-style retail account: tight low-mean outgoing distribution + the T4 CoP record. */
    private String freshAccountA() {
        String acct = "gp-A-" + RUN + "-" + SEQUENCE.incrementAndGet();
        putStats(acct, 12_633L, 2_000L, 12_633L, 1_500L, 287L, false, false);

        long firstSeenCd = now.toEpochMilli() - 200L * DAY_MS;
        putBaseline(acct, CD_CP, 60L, 12_960L, firstSeenCd, routine(12_960L, firstSeenCd));
        // KL is a long-standing supplier — first seen well over a year ago — so its recency signal
        // (B2, tau=60d) decays to effectively zero, matching the contract's "T2 → B2 ~ 0".
        long firstSeenKl = now.toEpochMilli() - 500L * DAY_MS;
        putBaseline(acct, KL_CP, 60L, 19_530L, firstSeenKl, routine(19_530L, firstSeenKl));

        // T4: established supplier "Acme Supplies Ltd" whose IBAN has changed (CoP mismatch).
        String supplierName = "Acme Supplies Ltd";
        String oldIban = "CY00OLDIBAN0000";
        String newIban = "CY00NEWIBAN0000";
        putByName(acct, supplierName, oldIban, 4L, 150_000L,
                now.toEpochMilli() - 40L * DAY_MS, now.toEpochMilli() - DAY_MS);
        // A3 keys on the new IBAN being paid; mirror the established €1500 mean onto it so the €4200
        // is anomalous versus the supplier's norm.
        putBaseline(acct, newIban, 4L, 150_000L, now.toEpochMilli() - 40L * DAY_MS,
                List.of(new RecentPayment(150_000L, now.toEpochMilli() - 30L * DAY_MS),
                        new RecentPayment(150_000L, now.toEpochMilli() - 10L * DAY_MS)));
        return acct;
    }

    /** Holds the term deposit; empty outgoing baseline so A1 stays quiet on the T5b sweep. */
    private String freshAccountB() {
        String acct = "gp-B-" + RUN + "-" + SEQUENCE.incrementAndGet();
        putStats(acct, 0L, 0L, 0L, 0L, 0L, false, false);
        return acct;
    }

    /** The romance escalation baseline. No designated approver, so the HOLD is not re-routed. */
    private String freshAccountC() {
        String acct = "gp-C-" + RUN + "-" + SEQUENCE.incrementAndGet();
        putStats(acct, 50_000L, 20_000L, 50_000L, 20_000L, 30L, false, false);

        String cpKey = "CY33ROMANCE0000";
        long firstSeen = now.toEpochMilli() - 14L * DAY_MS;
        putBaseline(acct, cpKey, 4L, 62_500L, firstSeen, List.of(
                new RecentPayment(20_000L, now.toEpochMilli() - 14L * DAY_MS),
                new RecentPayment(40_000L, now.toEpochMilli() - 11L * DAY_MS),
                new RecentPayment(70_000L, now.toEpochMilli() - 6L * DAY_MS),
                new RecentPayment(120_000L, now.toEpochMilli() - 2L * DAY_MS)));
        return acct;
    }

    private List<RecentPayment> routine(long amountCents, long firstSeenMs) {
        return List.of(
                new RecentPayment(amountCents, firstSeenMs),
                new RecentPayment(amountCents, firstSeenMs + 30 * DAY_MS),
                new RecentPayment(amountCents, firstSeenMs + 60 * DAY_MS),
                new RecentPayment(amountCents, firstSeenMs + 90 * DAY_MS),
                new RecentPayment(amountCents, firstSeenMs + 120 * DAY_MS),
                new RecentPayment(amountCents, firstSeenMs + 150 * DAY_MS));
    }

    private void putStats(String accountId, long mean, long std, long median, long mad, long count,
                          boolean business, boolean approver) {
        AccountStatsItem item = new AccountStatsItem();
        item.setPk("ACC#" + accountId);
        item.setSk("META");
        item.setOutMeanAmountCents(mean);
        item.setOutStdAmountCents(std);
        item.setOutMedianAmountCents(median);
        item.setOutMadAmountCents(mad);
        item.setOutTxnCount(count);
        item.setBusinessAccount(business);
        item.setHasDesignatedApprover(approver);
        item.setApproverUserIds(List.of());
        item.setSource("CONSTRUCTED");
        statsTable.putItem(item);
    }

    private void putBaseline(String accountId, String cpKey, long payCount, long meanCents,
                             long firstSeenMs, List<RecentPayment> recent) {
        CounterpartyBaselineItem item = new CounterpartyBaselineItem();
        item.setPk("ACC#" + accountId);
        item.setSk("CP#" + cpKey);
        item.setAccountId(accountId);
        item.setCounterpartyKey(cpKey);
        item.setCounterpartyIban(cpKey);
        item.setPayCount(payCount);
        item.setMeanAmountCents(meanCents);
        item.setStdAmountCents(Math.max(1L, meanCents / 10));
        item.setFirstSeenEpochMs(firstSeenMs);
        item.setLastSeenEpochMs(now.toEpochMilli() - DAY_MS);
        item.setRecentPayments(recent);
        item.setStandingOrder(false);
        item.setSource("CONSTRUCTED");
        baselineTable.putItem(item);
    }

    private void putByName(String accountId, String displayName, String establishedKey, long payCount,
                           long meanCents, long firstSeenMs, long lastSeenMs) {
        String normalized = displayName.trim().toLowerCase().replaceAll("\\s+", " ");
        CounterpartyByNameItem item = new CounterpartyByNameItem();
        item.setPk("ACC#" + accountId);
        item.setSk("NAME#" + normalized.toUpperCase());
        item.setNormalizedName(normalized);
        item.setDisplayName(displayName);
        item.setEstablishedIban(establishedKey);
        item.setEstablishedCounterpartyKey(establishedKey);
        item.setPayCount(payCount);
        item.setMeanAmountCents(meanCents);
        item.setFirstSeenEpochMs(firstSeenMs);
        item.setLastSeenEpochMs(lastSeenMs);
        item.setSource("CONSTRUCTED");
        byNameTable.putItem(item);
    }

    private void seedFreedFundsPosture(String accountId, long freedCents, Instant lastBreak) {
        AccountPostureItem item = new AccountPostureItem();
        item.setPk("ACC#" + accountId);
        item.setSk("POSTURE");
        item.setFundsFreedEur72hCents(freedCents);
        item.setLastDepositBreakEpochMs(lastBreak.toEpochMilli());
        item.setTtlEpochSec((now.toEpochMilli() + 72L * 60L * 60L * 1000L) / 1000L);
        postureTable.putItem(item);
    }

    // ---------------------------------------------------------------------------------------------
    // HTTP helpers
    // ---------------------------------------------------------------------------------------------

    private Decision post(String body, String token, HttpStatus expected) {
        ResponseEntity<Decision> resp = http.post()
                .uri("/decision")
                .headers(h -> {
                    h.setBearerAuth(token);
                    h.setContentType(MediaType.APPLICATION_JSON);
                })
                .body(body)
                .retrieve()
                .toEntity(Decision.class);
        assertEquals(expected, resp.getStatusCode(), "unexpected HTTP status");
        assertNotNull(resp.getBody(), "decision body must be present");
        return resp.getBody();
    }

    private ResponseEntity<String> postRaw(String body, String token) {
        return http.post()
                .uri("/decision")
                .headers(h -> {
                    h.setBearerAuth(token);
                    h.setContentType(MediaType.APPLICATION_JSON);
                })
                .body(body)
                .exchange((req, res) -> {
                    String payload = new String(res.getBody().readAllBytes());
                    return new ResponseEntity<>(payload, res.getHeaders(), res.getStatusCode());
                });
    }

    private String token(String sub, Role role, String accountId) {
        return jwtService.issue(sub, role, accountId, Duration.ofHours(1));
    }

    private String uniqueId(String prefix) {
        return prefix + "-" + SEQUENCE.incrementAndGet();
    }

    private static double signal(Decision d, String id) {
        return d.engineVerdict().signals().stream()
                .filter(s -> s.id().equals(id))
                .mapToDouble(com.cy.diakritis.common.dto.Signal::value)
                .findFirst()
                .orElse(0.0);
    }

    // ---------------------------------------------------------------------------------------------
    // Request bodies (snake_case wire format; the custom ActionEvent deserializer resolves payloads)
    // ---------------------------------------------------------------------------------------------

    private String transferBody(String eventId, String accountId, String cpKey, String resolvedName,
                                Instant beneficiaryCreatedAt, long amountEur, long availableEur) {
        String resolvedNameJson = resolvedName == null ? "null" : "\"" + resolvedName + "\"";
        String benCreated = beneficiaryCreatedAt == null
                ? ""
                : ",\"beneficiary_created_at\":\"" + beneficiaryCreatedAt + "\"";
        return """
                {"event_id":"%s","account_id":"%s","event_type":"TRANSFER",
                 "payload":{"counterparty":{"addressing":"IBAN","value":"%s","resolved_account_ref":"%s",
                   "resolved_name":%s,"display_name":"Payee"%s},
                   "amount_eur":%d,"available_balance_eur":%d,"rail":"SEPA"},
                 "context":{"ts":"%s","session_id":"sess-%s","channel":"MOBILE_APP","ip":"203.0.113.7",
                   "device":{"device_id":"dev-1","platform":"IOS"}}}
                """.formatted(eventId, accountId, cpKey, cpKey, resolvedNameJson, benCreated,
                amountEur, availableEur, FROZEN_TS, eventId);
    }

    private String depositBreakBody(String eventId, String accountId, String depositId,
                                    long principalEur, long penaltyEur) {
        Instant maturity = now.plus(Duration.ofDays(180));
        return """
                {"event_id":"%s","account_id":"%s","event_type":"TERM_DEPOSIT_BREAK",
                 "payload":{"deposit_id":"%s","principal_eur":%d,"maturity_date":"%s","penalty_eur":%d},
                 "context":{"ts":"%s","session_id":"sess-%s","channel":"MOBILE_APP","ip":"203.0.113.9",
                   "device":{"device_id":"dev-b","platform":"ANDROID"}}}
                """.formatted(eventId, accountId, depositId, principalEur, maturity, penaltyEur,
                FROZEN_TS, eventId);
    }
}
