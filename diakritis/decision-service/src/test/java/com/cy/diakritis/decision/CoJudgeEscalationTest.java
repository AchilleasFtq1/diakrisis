package com.cy.diakritis.decision;

import com.cy.diakritis.common.dto.ActionEvent;
import com.cy.diakritis.common.dto.Agreement;
import com.cy.diakritis.common.dto.Decision;
import com.cy.diakritis.common.dto.EngineVerdict;
import com.cy.diakritis.common.persistence.AccountStatsItem;
import com.cy.diakritis.common.persistence.CounterpartyBaselineItem;
import com.cy.diakritis.common.persistence.RecentPayment;
import com.cy.diakritis.common.persistence.Tables;
import com.cy.diakritis.common.security.JwtService;
import com.cy.diakritis.common.security.Role;
import com.cy.diakritis.engine.judge.AiCoJudge;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T14 — co-judge divergence escalation. With a test {@link AiCoJudge} stub that returns
 * {@code DIVERGE_STRICTER @88} (≥ the {@code AI_ESCALATION_THRESHOLD} of 80), an engine CONFIRM must
 * be escalated exactly one band to a combined HOLD, while the engine verdict itself is unchanged
 * (the engine is authoritative; the AI can only ever make the outcome stricter, capped at HOLD).
 *
 * <p>This lives in its own Spring context because it overrides the production
 * {@code UnavailableAiCoJudge} with a deterministic stub; the rest of the golden-path suite asserts
 * the {@code UNAVAILABLE} default. The stub is a real, fully-implemented co-judge (not a test mock of
 * a missing method) — it always returns the same confident stricter opinion.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "diakrisis.jwt.secret=diakrisis-golden-path-test-secret-key-0123456789",
        "diakrisis.dynamo.endpoint=http://localhost:8000",
        "diakrisis.dynamo.region=us-east-1",
        "diakrisis.dynamo.auto-create=true",
        "diakrisis.models-dir=/Users/achilleaseftychiou/Documents/Projects/diakrisis/diakrisis-models"
})
class CoJudgeEscalationTest {

    private static final long DAY_MS = 24L * 60L * 60L * 1000L;
    private static final AtomicLong SEQUENCE = new AtomicLong();
    private static final String RUN = Long.toString(System.nanoTime(), 36);
    private static final String FROZEN_TS = "2026-06-13T12:00:00Z";
    private static final String CD_CP = "CD|46939146";

    /**
     * A deterministic stricter co-judge: it always returns a confident DIVERGE_STRICTER @88 opinion,
     * which the combine rule must escalate the engine band by one (capped at HOLD). It is marked
     * {@link Primary} so it replaces the production {@code UnavailableAiCoJudge} in this context only.
     */
    @TestConfiguration
    static class StricterCoJudgeConfig {
        @Bean
        @Primary
        AiCoJudge stricterCoJudge() {
            return new AiCoJudge() {
                @Override
                public Opinion opine(ActionEvent event, EngineVerdict verdict) {
                    return new Opinion(88, com.cy.diakritis.common.dto.Verdict.HOLD,
                            "stub stricter co-judge", Agreement.DIVERGE_STRICTER, STATUS_AVAILABLE);
                }
            };
        }
    }

    @LocalServerPort
    private int port;

    @Autowired
    private DynamoDbEnhancedClient enhanced;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private JsonMapper jsonMapper;

    private RestClient http;
    private Instant now;
    private DynamoDbTable<CounterpartyBaselineItem> baselineTable;
    private DynamoDbTable<AccountStatsItem> statsTable;

    @BeforeEach
    void setUp() {
        this.now = Instant.now();
        this.baselineTable = enhanced.table(Tables.COUNTERPARTY_BASELINE,
                TableSchema.fromBean(CounterpartyBaselineItem.class));
        this.statsTable = enhanced.table(Tables.ACCOUNT_STATS,
                TableSchema.fromBean(AccountStatsItem.class));
        JacksonJsonHttpMessageConverter converter = new JacksonJsonHttpMessageConverter(jsonMapper);
        this.http = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .messageConverters(converters -> {
                    converters.removeIf(c -> c instanceof JacksonJsonHttpMessageConverter);
                    converters.add(converter);
                })
                .build();
    }

    @Test
    void t14_engineConfirmEscalatedToHoldByConfidentStricterCoJudge() {
        // A T3-style anomalous-amount-to-known-payee event → engine CONFIRM. The stub co-judge returns
        // DIVERGE_STRICTER @88, so the combined decision must escalate to HOLD (one band), capped.
        String acct = freshAccountA();
        Decision d = post(transferBody(uniqueId("t14"), acct, CD_CP, "CD Supplier", 700, 3200),
                token("customer-A", acct), HttpStatus.OK);

        assertEquals("CONFIRM", d.engineVerdict().decision().name(),
                "T14 engine must remain CONFIRM (authoritative) but was " + d.engineVerdict().decision());
        assertEquals("DIVERGE_STRICTER", d.aiCoJudge().agreement().name(),
                "T14 co-judge must report DIVERGE_STRICTER");
        assertEquals(88, d.aiCoJudge().score(), "T14 co-judge score must be 88");
        assertEquals("HOLD", d.combined().decision().name(),
                "T14 combined must escalate CONFIRM → HOLD but was " + d.combined().decision());
        assertNotNull(d.combined().basis(), "T14 combined must carry an escalation basis");
        assertTrue(d.combined().basis().contains("CONFIRM")
                        && d.combined().basis().contains("HOLD")
                        && d.combined().basis().contains("DIVERGE_STRICTER")
                        && d.combined().basis().contains("88"),
                "T14 basis must describe the escalation but was '" + d.combined().basis() + "'");
    }

    private String freshAccountA() {
        String acct = "cj-A-" + RUN + "-" + SEQUENCE.incrementAndGet();
        AccountStatsItem stats = new AccountStatsItem();
        stats.setPk("ACC#" + acct);
        stats.setSk("META");
        stats.setOutMeanAmountCents(12_633L);
        stats.setOutStdAmountCents(2_000L);
        stats.setOutMedianAmountCents(12_633L);
        stats.setOutMadAmountCents(1_500L);
        stats.setOutTxnCount(287L);
        stats.setBusinessAccount(false);
        stats.setHasDesignatedApprover(false);
        stats.setApproverUserIds(List.of());
        stats.setSource("CONSTRUCTED");
        statsTable.putItem(stats);

        long firstSeen = now.toEpochMilli() - 200L * DAY_MS;
        CounterpartyBaselineItem cp = new CounterpartyBaselineItem();
        cp.setPk("ACC#" + acct);
        cp.setSk("CP#" + CD_CP);
        cp.setAccountId(acct);
        cp.setCounterpartyKey(CD_CP);
        cp.setCounterpartyIban(CD_CP);
        cp.setPayCount(60L);
        cp.setMeanAmountCents(12_960L);
        cp.setStdAmountCents(1_296L);
        cp.setFirstSeenEpochMs(firstSeen);
        cp.setLastSeenEpochMs(now.toEpochMilli() - DAY_MS);
        cp.setRecentPayments(List.of(
                new RecentPayment(12_960L, firstSeen),
                new RecentPayment(12_960L, firstSeen + 30 * DAY_MS),
                new RecentPayment(12_960L, firstSeen + 60 * DAY_MS)));
        cp.setStandingOrder(false);
        cp.setSource("CONSTRUCTED");
        baselineTable.putItem(cp);
        return acct;
    }

    private String transferBody(String eventId, String accountId, String cpKey, String resolvedName,
                                long amountEur, long availableEur) {
        return """
                {"event_id":"%s","account_id":"%s","event_type":"TRANSFER",
                 "payload":{"counterparty":{"addressing":"IBAN","value":"%s","resolved_account_ref":"%s",
                   "resolved_name":"%s","display_name":"Payee"},
                   "amount_eur":%d,"available_balance_eur":%d,"rail":"SEPA"},
                 "context":{"ts":"%s","session_id":"sess-%s","channel":"MOBILE_APP","ip":"203.0.113.7",
                   "device":{"device_id":"dev-1","platform":"IOS"}}}
                """.formatted(eventId, accountId, cpKey, cpKey, resolvedName, amountEur, availableEur,
                FROZEN_TS, eventId);
    }

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

    private String token(String sub, String accountId) {
        return jwtService.issue(sub, Role.CUSTOMER, accountId, Duration.ofHours(1));
    }

    private String uniqueId(String prefix) {
        // Namespace per run: the Decisions table persists across runs in the shared DynamoDB Local, so
        // a fixed id would replay a stored decision instead of re-scoring on a re-run.
        return prefix + "-" + RUN + "-" + SEQUENCE.incrementAndGet();
    }
}
