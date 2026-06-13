export const meta = {
  name: 'diakrisis-build',
  description: 'Build the Diakrisis 2-service fraud-decision system (Maven reactor, DynamoDB, JWT) and pass golden-path T1-T6',
  phases: [
    { title: 'Wave1-Foundation', detail: 'parent pom + common module (DTOs, JWT, Dynamo), remove gradle skeleton' },
    { title: 'Wave2-Modules', detail: 'engine + etl + bank-app in parallel' },
    { title: 'Wave3-DecisionService', detail: 'decision-service controllers, lifecycle, idempotency' },
    { title: 'Wave4-TestsVerify', detail: 'golden-path T1-T6 tests, e2e boot, verify green' },
  ],
}

const ROOT = '/Users/achilleaseftychiou/Documents/Projects/diakrisis/diakritis'
const MODELS = '/Users/achilleaseftychiou/Documents/Projects/diakrisis/diakrisis-models'

// ============================ SHARED CONTRACT ============================
// Every agent receives this verbatim. It is the single source of truth for
// cross-module names/signatures so the reactor compiles end to end.
const CONTRACT = `
PROJECT ROOT: ${ROOT}   (this IS the Maven reactor root; cd here for all mvn)
PRE-TRAINED MODELS (load, NEVER train): ${MODELS}
  m1/m1.model = Java-serialized smile.classification.GradientTreeBoost (Smile 3.1.1)
  m1/columns.txt (16 feature names, exact order), m1/isotonic.csv (threshold,value step fn),
  m1/percentiles.csv (percentile 0-100 -> score). FEATURE_SPEC.md has the 16-feature formulas.

ABSOLUTE RULES (never violate):
- NO dummy code, NO stubs, NO TODO comments, NO @Disabled tests, NO placeholder methods.
  Everything you write must be fully implemented and compile. If something is out of scope,
  do not include it at all (do not stub it).
- Real Berka data where it exists; the ONLY constructed (disclosed) data is: T4 CoP name,
  T5 term deposit, T6 escalation sequence. Mark constructed items source="CONSTRUCTED",
  real items source="BERKA".
- Java 26, Spring Boot 4.1.0, Maven. Do NOT use preview/incubator APIs.
- Money as integer euro-cents (long). Timestamps as epoch-millis (long) in stores; Instant in DTOs.
- Jackson global SNAKE_CASE; DTOs are Java records with @JsonInclude(NON_NULL) and Bean Validation.

REACTOR LAYOUT (groupId com.cy.diakritis, version 0.1.0-SNAPSHOT, base package com.cy.diakritis):
  pom.xml (packaging pom) modules: common, engine, etl, decision-service, bank-app
  common/   (jar lib)   package com.cy.diakritis.common.{dto,security,persistence}
  engine/   (jar lib)   package com.cy.diakritis.engine.{signal,typology,band,m1,judge,pipeline,store}
  etl/      (jar CLI)    package com.cy.diakritis.etl
  decision-service/ (Spring Boot app :8081) package com.cy.diakritis.decision
  bank-app/ (Spring Boot app :8080)         package com.cy.diakritis.bank
  Only decision-service + bank-app apply spring-boot-maven-plugin (goal repackage).
  Parent pom dependencyManagement imports: spring-boot-dependencies:4.1.0 (pom import),
  software.amazon.awssdk:bom:2.31.78 (pom import); plus pinned: com.haifengl:smile-core:3.1.1,
  io.jsonwebtoken:jjwt-api/impl/jackson:0.12.6, org.apache.commons:commons-csv:1.12.0.
  Properties: java.version=26, maven.compiler.release=26.

COMMON DTOs (com.cy.diakritis.common.dto) - EXACT shapes (records):
  enum EventType { TRANSFER, P2P_TRANSFER, MASS_PAYMENT, TERM_DEPOSIT_BREAK, BENEFICIARY_ADD, LIMIT_CHANGE }
  enum Rail { SEPA, INSTANT, INTERNAL, P2P }
  enum Addressing { IBAN, ACCOUNT, MSISDN, EMAIL }
  enum Channel { WEB, MOBILE_APP }
  enum Platform { IOS, ANDROID, WEB }
  enum Verdict { ALLOW, CONFIRM, HOLD, BLOCK, REQUIRE_APPROVAL }
  enum Agreement { CONCUR, DIVERGE_STRICTER, DIVERGE_SOFTER }
  enum LifecycleState { DECIDED, EXECUTED, PENDING_CONFIRM, ABANDONED, PENDING_APPROVAL, REJECTED, EXPIRED, HELD, CANCELLED, LOCKED, REVIEW }
  record Counterparty(Addressing addressing, String value, String resolvedAccountRef,
        String resolvedName, String displayName, Instant beneficiaryCreatedAt) {}
  record DeviceInfo(@NotBlank String deviceId, @NotNull Platform platform) {}
  record SessionContext(@NotNull Instant ts, @NotBlank String sessionId, @NotNull Channel channel,
        String ip, @NotNull @Valid DeviceInfo device) {}
  sealed interface ActionPayload permits TransferPayload, MassPaymentPayload, DepositBreakPayload, BeneficiaryAddPayload, LimitChangePayload {}
  record TransferPayload(@NotNull @Valid Counterparty counterparty, @NotNull @Positive BigDecimal amountEur,
        @NotNull @PositiveOrZero BigDecimal availableBalanceEur, @NotNull Rail rail) implements ActionPayload {}
  record BatchItem(@NotBlank String itemId, @NotNull @Valid Counterparty counterparty, @NotNull @Positive BigDecimal amountEur) {}
  record MassPaymentPayload(@NotBlank String batchId, String purposeHint, @NotEmpty @Valid List<BatchItem> items,
        @NotNull @Positive BigDecimal totalEur, @NotNull @PositiveOrZero BigDecimal availableBalanceEur, @NotNull Rail rail) implements ActionPayload {}
  record DepositBreakPayload(@NotBlank String depositId, @NotNull @Positive BigDecimal principalEur,
        @NotNull Instant maturityDate, @PositiveOrZero BigDecimal penaltyEur) implements ActionPayload {}
  record BeneficiaryAddPayload(@NotNull @Valid Counterparty counterparty) implements ActionPayload {}
  record LimitChangePayload(@NotNull @Positive BigDecimal currentLimitEur, @NotNull @Positive BigDecimal newLimitEur) implements ActionPayload {}
  record ActionEvent(@NotBlank String eventId, @NotBlank String accountId, @NotNull EventType eventType,
        @NotNull @Valid ActionPayload payload, @NotNull @Valid SessionContext context) {}
  // ActionPayload polymorphism: deserialize the concrete payload from eventType. Implement a custom
  // Jackson deserializer on ActionEvent (or @JsonTypeInfo) that picks the payload class by eventType.
  record Signal(String id, double value, double weight, double contribution, String detail) {}
  record EngineVerdict(int score, Verdict decision, boolean scaExempt, String scaExemptBasis,
        List<String> typologies, List<Signal> signals) {}
  record AiCoJudge(Integer score, Verdict decision, String reason, Agreement agreement, String status) {}
  record Combined(Verdict decision, String basis, String reasonCode, String reviewFlag) {}
  record HoldInfo(int durationMinutes, Instant expiresAt, String cancelEndpoint, String releaseEndpoint) {}
  record ApprovalInfo(String reason, String approveEndpoint, String rejectEndpoint, int expiresInHours) {}
  record Lifecycle(LifecycleState state, Boolean executed, HoldInfo hold, ApprovalInfo approval) {}
  record ItemResult(String itemId, Verdict decision, List<Signal> signals) {}
  record Explanation(String customer, String audit) {}
  record Decision(String eventId, EngineVerdict engineVerdict, AiCoJudge aiCoJudge, Combined combined,
        Lifecycle lifecycle, Explanation explanation, List<ItemResult> items, String reasonCode, long latencyMs) {}

COMMON SECURITY (com.cy.diakritis.common.security):
  enum Role { CUSTOMER, APPROVER, OPS }
  record AuthPrincipal(String userId, Role role, String accountId) {}
  JwtService: HS256 via io.jsonwebtoken (Keys.hmacShaKeyFor(secret>=32 bytes)).
     String issue(String sub, Role role, String accountId, Duration ttl)  // claims: sub, role, accountId
     AuthPrincipal verify(String token)  // throws JwtException on invalid
  JwtAuthFilter extends org.springframework.web.filter.OncePerRequestFilter: read Authorization Bearer,
     verify, stash AuthPrincipal as request attribute "principal"; skip public paths /auth/login, /actuator/**.
     Secret from property diakrisis.jwt.secret (env DIAKRISIS_JWT_SECRET).
  Provide a request-scoped holder (or ThreadLocal cleared in filter finally) carrying the raw bearer token
     so bank-app can forward it to decision-service.

COMMON PERSISTENCE (com.cy.diakritis.common.persistence): AWS SDK v2 enhanced client.
  DynamoProperties (@ConfigurationProperties "diakrisis.dynamo"): String endpoint (http://localhost:8000),
     String region (us-east-1), boolean autoCreate (true).
  DynamoConfigSupport: static DynamoDbClient client(DynamoProperties) using endpointOverride + dummy creds
     StaticCredentialsProvider.create(AwsBasicCredentials.create("local","local")); and
     DynamoDbEnhancedClient enhanced(DynamoDbClient).
  Table name constants class Tables { CounterpartyBaseline, AccountStats, CounterpartyByName, Decisions,
     Observations, AccountPosture, CounterpartyReputation, Accounts, Payees, Cases }.
  @DynamoDbBean item classes (mutable POJOs with getters/setters; @DynamoDbPartitionKey/@DynamoDbSortKey):
     CounterpartyBaselineItem(pk "ACC#acct", sk "CP#cpKey"; accountId, counterpartyKey, counterpartyIban,
        resolvedName, expectedCopName, payCount(long), meanAmountCents(long), stdAmountCents, firstSeenEpochMs,
        lastSeenEpochMs, recentPayments(List<RecentPayment @DynamoDbBean {amountCents,epochMs}>),
        isStandingOrder(boolean), source(String))
     AccountStatsItem(pk "ACC#acct", sk "META"; outMeanAmountCents, outStdAmountCents, outMedianAmountCents,
        outMadAmountCents, outTxnCount, isBusinessAccount(boolean), hasDesignatedApprover(boolean),
        approverUserIds(List<String>), source)
     CounterpartyByNameItem(pk "ACC#acct", sk "NAME#NORMNAME"; normalizedName, displayName, establishedIban,
        establishedCounterpartyKey, payCount, meanAmountCents, firstSeenEpochMs, lastSeenEpochMs, source)
     DecisionItem(pk "EVENT#id", sk "DECISION"; eventId, accountId, initiatorSub, createdEpochMs, responseJson(String),
        lifecycleState(String), holdExpiresEpochMs(long))
     ObservationItem(pk "OBS#acct", sk "KIND#value"; accountId, kind, value, firstSeenEpochMs, lastSeenEpochMs,
        lastResolvedAccountRef, sessionId, ttlEpochSec(long))
     AccountPostureItem(pk "ACC#acct", sk "POSTURE"; fundsFreedEur72hCents(long), limitRaised72hCents,
        beneficiaryAddCount72h(long), lastDepositBreakEpochMs, ttlEpochSec)
     CounterpartyReputationItem(pk "CP#cpKey", sk "REP"; counterpartyKey, lastFlagEpochMs, worstOutcome, flagCount, ttlEpochSec)
     AccountItem(pk "ACC#acct", sk "META"; displayName, availableBalanceCents, ownerUserId, isBusiness,
        approverUserIds(List<String>), termDeposits(List<TermDeposit @DynamoDbBean {depositId,principalCents,maturityEpochMs,penaltyCents,broken}>), source)
     PayeeItem(pk "ACC#acct", sk "PAYEE#cpKey"; iban, displayName, resolvedName, createdEpochMs, addedInSessionId, source)
     CaseItem(pk "CASE#id", sk "CASE"; eventId, state, initiatorUserId, approverUserId, holdExpiryEpochMs,
        batchHeldItemIds(List<String>), createdEpochMs)
  TableBootstrap: create-if-missing each table (DescribeTable -> on ResourceNotFoundException CreateTable
     PAY_PER_REQUEST + wait ACTIVE). Provide one method createIfMissing(DynamoDbClient, schema list).

ENGINE (com.cy.diakritis.engine):
  Identity: resolved counterparty key = counterparty.resolvedAccountRef if present else addressing+"|"+value.
  store.FeatureStore (interface, read-only) impl in decision-service over DynamoDB:
     long priorPaymentCount(String accountId, String cpKey)
     long meanAmountCents(String accountId, String cpKey)
     Optional<Long> firstSeenEpochMs(String accountId, String cpKey)
     List<RecentPayment> recentPayments(String accountId, String cpKey)
     AccountStatsView accountStats(String accountId)  // mean/median/mad/std cents, business+approver flags
     Optional<CounterpartyByNameView> byName(String accountId, String normalizedName)  // T4 dual key
  store.RuntimeState (in-memory, per process): rolling 24h deque per (accountId,cpKey) of {amountCents,epochMs};
     long logicalAmountCents(accountId, cpKey, thisAmountCents, now) = max(thisAmount, sum over last 24h incl this);
     record(accountId,cpKey,amountCents,now). Per-session beneficiary-add timestamps for B3.
  store.PostureView { long fundsFreedEur72hCents; ... }  read from AccountPosture table.
  signal.Signal interface { String id(); double weight(); double value(SignalContext ctx); }
  signal.SignalContext { ActionEvent event; FeatureStore store; RuntimeState runtime; PostureView posture;
     ObservationsView obs; String cpKey; long logicalAmountCents; long amountCents; long availableBalanceCents; Instant now; }
  band.Weights (named int constants): B1=14,B2=10,B3=8,B4=-12,B5=16,P1=22,A1=12,A2=18,A3=12,A4=6,V1=8,V2=10,
     C1=6,C3=8,G1=12,G2=6,D1=10,D2=6,K1=16,K2=10,K3=8,MP1=16,MP2=12,MP4=14,M1_CAP=18,M2_CAP=12,X1=20;
     AI_ESCALATION_THRESHOLD=80, TY2_ESTABLISHED_MIN_PAYMENTS=3, TY2_ESTABLISHED_MIN_AGE_DAYS=30,
     B2_DECAY_TAU_DAYS=60, A2_DRAIN_TELL=0.8, LOGICAL_AMOUNT_WINDOW_HOURS=24, HOLD_DEFAULT_MINUTES=30, X1_HALFLIFE_HOURS=6.
  band.Band enum { ALLOW, CONFIRM, HOLD, BLOCK }; Bands.bandFor(int score, Rail rail): edges {30,60,85},
     if rail INSTANT||P2P subtract 8 from each edge; capNonMonetary(Band, EventType) -> min(band,CONFIRM)
     for TERM_DEPOSIT_BREAK,BENEFICIARY_ADD,LIMIT_CHANGE.
  SIGNALS to implement fully (value in [0,1]):
     B1: priorPaymentCount(cpKey)==0 ? 1 : 0
     B2: exp(-ageDays / B2_DECAY_TAU_DAYS) from firstSeen; if new identity (no history) age=0 -> 1.0
     B3: beneficiaryCreatedAt within current session/last few minutes ? 1 : 0
     B4: (payCount>=20 || ageDays>90) ? 1 : 0   (negative weight credits trust)
     B5: resolvedName present AND CoP-expected name exists AND differs ? 1 : 0
     A1: robust-z of logicalAmount vs account outgoing median/MAD -> clamp((z-2)/4) to [0,1]
     A2: clamp((logicalAmount/available - 0.5)/0.55) to [0,1]   // 0.974->0.86, <=0.5 -> 0
     A3: robust-z of logicalAmount vs this counterparty mean/MAD -> clamp((z-2)/4); if no history -> 0
     V2: monotonic rising amounts to same cpKey across >=2 distinct days -> 1 else 0 (use recentPayments)
     K1: posture.fundsFreedEur72hCents >= amount ? min(1, fundsFreed/amount) with 72h decay : 0
     M1: M1Scorer percentile value (see below), capped contribution at 18
  typology.TypologyEvaluator.evaluate(Map<String,Double> values, SignalContext ctx) -> List<String>:
     Ty2 "invoice_redirection": B5==1 && A3>0 && established (byName payCount>=3 && ageDays>=30)
     Ty4 "romance_repeat_victim": V2>0 && B2>0.4
     Ty5 "liquidation_kill_chain": K1>0.6 && B1==1 && A2>0.6
     Ty1 "safe_account_scam": B1==1 && A2>0.7 && (B3>0)
  pipeline.ScoreEngine.score(...) order: runtime.record current event -> compute signals ->
     raw=clip(sum contributions,0,100) -> typologies -> band=Bands.bandFor(raw,rail) ->
     typology override: 1 match -> band=HOLD; >=2 matches AND raw>=85 -> BLOCK ->
     capNonMonetary -> if TERM_DEPOSIT_BREAK force CONFIRM and drop HOLD-pinning typologies ->
     if band==HOLD && account.hasDesignatedApprover -> REQUIRE_APPROVAL ->
     policy: MASS_PAYMENT on business account -> REQUIRE_APPROVAL; LIMIT_CHANGE newLimit>2*current -> REQUIRE_APPROVAL ->
     scaExempt = (decision==ALLOW && eventType TRANSFER/P2P) with basis "PSD2 RTS Art.18 TRA (low value, low fraud-rate)".
     Build EngineVerdict + reasonCode (Ty5->DKR-KILLCHAIN, Ty2->DKR-INVOICE, X1->DKR-XACCT, ALLOW->MS03 absent, else FRAD/DKR-SAFEACCT).
     explanation.customer: null iff ALLOW; else a one-sentence pattern-naming message (purpose prompt for TERM_DEPOSIT_BREAK).
  m1.M1Scorer: load on construct from modelsDir (property diakrisis.models-dir default ${MODELS}).
     ObjectInputStream -> smile.classification.GradientTreeBoost. Features.toVector(ActionEvent,...) -> double[16]
     in columns.txt order. predict via model.predict(tuple, double[2] posteriori) using smile DataFrame single row
     OR the smile Tuple API; take posteriori[1] as raw prob. Apply isotonic.csv step fn then percentile-rank against
     percentiles.csv -> value in [0,1]. IF load/scoring throws -> value 0 (engine still works). Implement Features.java
     per FEATURE_SPEC.md (amt_log=ln(1+amt), hour/dow sin/cos from ts, c*_log from velocity counts (0 if none ->ln1=0),
     d1/d4/d10/d15 missing->-1 with d_miss_count, amt_ratio=amt/mean(prior same account) clip 50 first->1.0,
     email_missing/is_free_mail from counterparty). For T1-T6 velocity/device features may be 0/-1 (no obs) - that is REAL, not a stub.
  judge.AiCoJudge interface { AiCoJudge.Opinion opine(ActionEvent, EngineVerdict) }; one impl UnavailableAiCoJudge
     returns status "UNAVAILABLE" (the SDD resilience default - a real impl, not a stub).
  pipeline.CombineRule.combine(EngineVerdict engine, AiCoJudge ai) -> Combined:
     default combined=engine.decision; if ai null/UNAVAILABLE/CONCUR -> keep; DIVERGE_SOFTER -> keep;
     DIVERGE_STRICTER && ai.score>=80 -> escalate one band capped at HOLD (PASS->CONFIRM,CONFIRM->HOLD); else keep + reviewFlag.

DECISION-SERVICE (:8081):
  POST /decision (ActionEvent -> Decision). Idempotent: GetItem Decisions[EVENT#id]; hit -> return stored responseJson
     (200, no re-scoring, no posture mutation). miss -> score (engine + UnavailableAiCoJudge) -> conditional put
     attribute_not_exists(pk); winner commits posture/observation/reputation; loser GetItem returns stored.
     For TERM_DEPOSIT_BREAK: commit fundsFreedEur72hCents += principal-penalty to AccountPosture.
     For MASS_PAYMENT: per-item decisions in items[]; business account -> REQUIRE_APPROVAL.
  POST /actions/{id}/{confirm,cancel,release,approve,reject}: lifecycle in Cases/Decisions. approve requires
     JWT role APPROVER and sub != stored initiatorSub else 403 {"error":"SELF_APPROVAL_FORBIDDEN"}; release before
     holdExpiry -> 409 {"error":"LOCKED_PRE_EXPIRY"}; batch approve -> {items_executed, items_held}.
  GET /decisions/{id}/why -> audit/explanation.
  latencyMs stamped per decision (System.nanoTime). @RestControllerAdvice: validation/garbage -> 400/422,
     transitions -> 409, JwtException -> 401, self-approval -> 403, not found -> 404. NEVER 500 for known inputs.
  JwtAuthFilter registered (FilterRegistrationBean) validating same secret. Jackson SNAKE_CASE,
     FAIL_ON_UNKNOWN_PROPERTIES=true, JavaTimeModule. spring.threads.virtual.enabled=true. application.yml with
     server.port 8081, diakrisis.models-dir=${MODELS}, diakrisis.jwt.secret, diakrisis.dynamo.endpoint http://localhost:8000.

BANK-APP (:8080):
  POST /auth/login {username,password} -> {token,sub,roles,expiresAt} (mint via JwtService; demo users from seed).
  GET /accounts/{id}, GET /payees, POST /transfers, /p2p, /batches, /deposits/{id}/break, /limits/change:
     each builds an ActionEvent from stored Account/Payee facts and calls decision-service POST /decision via
     RestClient forwarding the inbound Bearer (ClientHttpRequestInterceptor copies Authorization). Returns Decision.
  GET /ops/feed, /ops/counters, /ops/approvals (role OPS/APPROVER). application.yml server.port 8080,
     diakrisis.decision-service.base-url http://localhost:8081, same jwt.secret + dynamo endpoint.

ETL (com.cy.diakritis.etl.BerkaEtl, plain main): args --berka-dir, --ddb-endpoint, --demo, --rebuild-features.
  Stream data/raw/berka/fin_trans.tsv (commons-csv TDF, never load all). Columns (headerless positional):
     trans_id, account_id, date(yyyy-MM-dd), amount, balance, type, operation, k_symbol, bank(2char), counterparty_account.
     operation=="ROB" is outgoing transfer; counterparty key = bank+"|"+counterparty_account; amount abs.
  Aggregate per (account,cpKey): count, mean/std (Welford), first/last epoch (parse date, zone UTC), recent 6.
  Per account: outgoing median/MAD/mean (keep per-account list of ROB amounts, finalize). fin_disp.tsv:
     account has OWNER+DISPONENT -> isBusiness + hasDesignatedApprover, approverUserIds from DISPONENT.
  Batch write feature tables (25 per batch). Idempotent putItem overwrite. Create tables if missing first.
  --demo writes the seed (see SEED) + demo users.

SEED (real Berka, 3 disclosed constructs):
  acc-A -> Berka account 7819 (retail). Real counterparties on 7819 (ROB):
     CD|46939146 (60 x 129.60 EUR), KL|64831554 (60 x 195.30), WX|14313167 (60 x 172.10), MN|77821957 (60 x 73.30).
     7819 overall outgoing: n=287 mean 126.33 max 195.30 (tight low-mean distribution).
     T1 pays CD|46939146 cpKey, amount 120, avail 4500 -> B4 fires, B1=0 -> ALLOW + scaExempt.
     T2 pays KL|64831554 cpKey, amount 750, avail 3200 -> B4, B2~0 -> ALLOW + scaExempt.
     T3 pays CD|46939146 cpKey, amount 700, avail 3200 -> A1+A3 fire (700 >> 126 mean) -> CONFIRM (30-59).
     T4 pays a NEW account-ref with resolvedName matching an established supplier name; seed CounterpartyByName
        ACC#acc-A NAME#... with payCount>=3, meanAmountCents=150000, firstSeen>=30d, establishedIban=<old>;
        T4 amount 4200 to new IBAN -> B1 + B5(name match, IBAN differ) + A3 -> Ty2 -> HOLD. (CoP name = CONSTRUCTED)
  acc-B -> Berka account 8261. Seed Account termDeposits=[{dep-001, principalCents 500000, maturity now+180d,
        penaltyCents 12500, broken=false}] (CONSTRUCTED), availableBalanceCents 498000.
     T5a TERM_DEPOSIT_BREAK dep-001 -> CONFIRM+purpose, commits fundsFreedEur72hCents += 487500.
     T5b TRANSFER 4850 to a brand-new payee (Payee createdEpochMs now-4min, no baseline) avail 4980 ->
        K1>0.6 + A2>0.6 + B1 + B2~1 + B3 -> Ty5 -> HOLD.
  acc-C -> Berka account 3834 (dual-access). Seed one CounterpartyBaseline with recentPayments rising
        [{20000,now-14d},{40000,now-11d},{70000,now-6d},{120000,now-2d}] payCount 4 firstSeen now-14d (CONSTRUCTED escalation).
     T6 TRANSFER 2000 to that cpKey -> V2>0 + B2>0.4 -> Ty4 -> HOLD.
  Demo users (JWT subjects, stored for /auth/login; password "demo"): customer-A(role CUSTOMER, acc-A),
     customer-B(acc-B), customer-C(acc-C), approver-biz(APPROVER), ops-user(OPS).

T1-T6 EXPECTED (assert engineVerdict.decision, band, combined==engine):
  T1 ALLOW score 0-29 scaExempt=true typologies=[] explanation.customer=null
  T2 ALLOW score 0-29 scaExempt=true explanation.customer=null
  T3 CONFIRM score 30-59 scaExempt=false A3 value>0 explanation.customer!=null
  T4 HOLD typologies contains invoice_redirection, B5 value=1.0 explanation.customer!=null
  T5a CONFIRM (never HOLD/BLOCK) explanation.customer!=null contains purpose prompt; commits fundsFreed
  T5b HOLD typologies contains liquidation_kill_chain, K1>0.6, A2>0.6, lifecycle.hold present
  T6 HOLD typologies contains romance_repeat_victim, V2>0, B2>0.4
  Contract invariants assertable: CI-1 idempotent replay identical body no double-mutation;
  CI-4 combined==engine (AI UNAVAILABLE); CI-7 explanation null iff ALLOW; CI-8 malformed->400/422 never 500;
  CI-9 non-monetary cap (T5a); CI-11 latencyMs<50.

BUILD COMMANDS (cd ${ROOT}):
  compile one module incl deps from reactor: mvn -q -pl <module> -am compile
  install common/engine to .m2 so peers resolve without rebuild: mvn -q -pl <module> -am install -DskipTests
  full: mvn -q clean package    (then java -jar <module>/target/*.jar)
`

// ============================ WAVE 1 ============================
phase('Wave1-Foundation')
const wave1 = await agent(
  `You are building Wave 1 (FOUNDATION) of the Diakrisis Maven reactor. Do the following, fully and compiling:
1. cd ${ROOT}. REMOVE the Gradle skeleton: build.gradle, settings.gradle, gradlew, gradlew.bat, gradle/, .gradle/, bin/, .classpath, .project, .settings/, and src/ (the old top-level src). KEEP data/, docs/, qa/, *.md, .gitignore, .diakrisis-build.workflow.js. Add target/ to .gitignore.
2. Create the parent pom.xml (packaging pom) with the module list [common, engine, etl, decision-service, bank-app] and the dependencyManagement/properties/pluginManagement exactly as in the CONTRACT.
3. Create docker-compose.yml with service dynamodb (image amazon/dynamodb-local, command for -sharedDb -inMemory, port 8000:8000).
4. Build the common/ module FULLY per the CONTRACT: all dto records (incl the ActionEvent custom Jackson deserializer that selects the concrete ActionPayload by eventType), all security classes (Role, AuthPrincipal, JwtService, JwtAuthFilter, a request-scoped/threadlocal bearer holder), all persistence classes (DynamoProperties, DynamoConfigSupport, Tables constants, every @DynamoDbBean item class listed, RecentPayment, TermDeposit nested beans, TableBootstrap create-if-missing). common is a plain jar (no spring-boot-maven-plugin). Add the needed deps to common/pom.xml: spring-boot-starter (for jackson + config-properties + slf4j; NOT web), spring-context, jakarta.servlet-api (provided), jakarta.validation-api, software.amazon.awssdk dynamodb-enhanced + dynamodb, jjwt-api/impl/jackson.
5. Run: mvn -q -pl common -am install -DskipTests  -- and ITERATE on any compile error until it succeeds. Do NOT return until common installs cleanly.
Report: the exact final public API you created (record/class/enum FQNs and key method signatures) so downstream modules compile against it, and confirm 'mvn install common' SUCCESS.

CONTRACT:
${CONTRACT}`,
  { label: 'wave1:common', phase: 'Wave1-Foundation' }
)
log('Wave 1 (foundation + common) complete')

// ============================ WAVE 2 (parallel) ============================
phase('Wave2-Modules')
const wave2 = await parallel([
  () => agent(
    `Wave 2: build the engine/ module FULLY (no stubs). Wave 1 finished; common is installed in .m2.
First: cd ${ROOT}; read the actual common sources under common/src/main/java to get exact signatures, and read
${MODELS}/FEATURE_SPEC.md, ${MODELS}/m1/columns.txt, ${MODELS}/m1/isotonic.csv (head), ${MODELS}/m1/percentiles.csv (head).
Implement per CONTRACT engine section: Weights, Band/Bands, signal.Signal + SignalContext, store.FeatureStore
(interface), store.RuntimeState (in-memory rolling 24h), store.PostureView/ObservationsView views, all SIGNALS
(B1,B2,B3,B4,B5,A1,A2,A3,V2,K1,M1), typology.TypologyEvaluator (Ty1,Ty2,Ty4,Ty5), pipeline.ScoreEngine (full
order incl typology override, non-monetary cap, TERM_DEPOSIT_BREAK guard, approval routing, scaExempt, reasonCode,
explanation), m1.M1Scorer + Features (load smile GBT, isotonic, percentile; resilient to load failure -> 0),
judge.AiCoJudge + UnavailableAiCoJudge, pipeline.CombineRule. engine/pom.xml deps: common, smile-core 3.1.1.
Write engine unit tests that load m1.model from ${MODELS} and assert M1Scorer returns a value in [0,1] for a
sample feature vector, and that Bands/typology logic is correct. Run: mvn -q -pl engine -am install -DskipTests,
then mvn -q -pl engine test. ITERATE until both succeed. Do NOT return until engine installs + tests pass.
Report engine public API (ScoreEngine.score signature, FeatureStore interface, SignalContext fields) for decision-service.
CONTRACT:
${CONTRACT}`,
    { label: 'wave2:engine', phase: 'Wave2-Modules' }
  ),
  () => agent(
    `Wave 2: build the etl/ module FULLY (no stubs). common is installed in .m2. cd ${ROOT}; read common item
classes under common/src/main/java/com/cy/diakritis/common/persistence to use the exact @DynamoDbBean classes.
Implement com.cy.diakritis.etl.BerkaEtl (plain public static void main) per CONTRACT etl + SEED sections:
stream data/raw/berka/fin_trans.tsv (commons-csv TDF), aggregate ROB counterparties + per-account stats, read
fin_disp.tsv for business/approver flags, write feature tables to DynamoDB (create-if-missing via common
TableBootstrap), and on --demo write the demo seed (acc-A->7819, acc-B->8261, acc-C->3834 mappings, the 3
CONSTRUCTED items, and the demo users into Accounts) re-anchoring age-sensitive timestamps to now. etl/pom.xml
deps: common, commons-csv, awssdk dynamodb-enhanced; add exec-maven-plugin (mainClass BerkaEtl) and
maven-shade or jar-with-dependencies so it runs as a jar. Run: mvn -q -pl etl -am package. ITERATE until it
compiles+packages. Do NOT return until etl packages cleanly. (Do not run it against DynamoDB now - that is Wave 4.)
Report the produced jar path and the exact main args.
CONTRACT:
${CONTRACT}`,
    { label: 'wave2:etl', phase: 'Wave2-Modules' }
  ),
  () => agent(
    `Wave 2: build the bank-app/ module FULLY (no stubs). common is installed in .m2. cd ${ROOT}; read common dto +
security sources for exact signatures. Implement com.cy.diakritis.bank Spring Boot app (:8080) per CONTRACT
bank-app section: BankAppApplication, JWT login (/auth/login minting via common JwtService, demo users loaded
from Accounts table or a seeded users map), JwtAuthFilter registration, a RestClient bean (base-url property)
with an interceptor forwarding the inbound Authorization bearer, controllers /accounts/{id}, /payees,
/transfers, /p2p, /batches, /deposits/{id}/break, /limits/change (each builds an ActionEvent and POSTs to
decision-service, returns the Decision), and /ops/feed,/ops/counters,/ops/approvals. application.yml per CONTRACT.
bank-app/pom.xml: common, spring-boot-starter-web, -validation, awssdk dynamodb-enhanced; spring-boot-maven-plugin
repackage. Jackson SNAKE_CASE + JavaTimeModule + virtual threads. Run: mvn -q -pl bank-app -am package -DskipTests.
ITERATE until it packages and the jar exists. Do NOT return until bank-app packages cleanly.
Report the controller endpoints implemented.
CONTRACT:
${CONTRACT}`,
    { label: 'wave2:bank-app', phase: 'Wave2-Modules' }
  ),
])
log('Wave 2 (engine + etl + bank-app) complete')

// ============================ WAVE 3 ============================
phase('Wave3-DecisionService')
const wave3 = await agent(
  `Wave 3: build the decision-service/ module FULLY (no stubs). common + engine are installed in .m2. cd ${ROOT};
read the actual engine sources (ScoreEngine, FeatureStore interface, SignalContext, store classes) and common
item classes to wire them. Implement com.cy.diakritis.decision Spring Boot app (:8081) per CONTRACT decision-service
section: DecisionServiceApplication, a DynamoFeatureStore implementing engine FeatureStore over the read-only feature
tables, repositories for Decisions/Observations/AccountPosture/CounterpartyReputation, the DecisionController
(POST /decision with idempotency: conditional put on Decisions, replay returns stored responseJson, winner commits
posture incl TERM_DEPOSIT_BREAK fundsFreed; MASS_PAYMENT per-item), the lifecycle controller (/actions/{id}/
confirm,cancel,release,approve,reject with the four-eyes 403 SELF_APPROVAL_FORBIDDEN and 409 LOCKED_PRE_EXPIRY
guards), GET /decisions/{id}/why, the @RestControllerAdvice (400/422/409/401/403/404, never 500), JwtAuthFilter
registration, a ScoreEngine @Bean wired with M1Scorer(models-dir) + UnavailableAiCoJudge + DynamoFeatureStore +
a singleton RuntimeState, latencyMs stamping, TableBootstrap on startup. decision-service/pom.xml: common, engine,
spring-boot-starter-web, -validation, awssdk dynamodb-enhanced; spring-boot-maven-plugin repackage. application.yml
per CONTRACT (models-dir ${MODELS}). Jackson SNAKE_CASE + JavaTimeModule, FAIL_ON_UNKNOWN_PROPERTIES true, virtual
threads. Run: mvn -q -pl decision-service -am package -DskipTests. ITERATE until it packages. Do NOT return until
decision-service packages cleanly (jar exists). Report the wiring (how ScoreEngine + FeatureStore + M1Scorer are constructed).
CONTRACT:
${CONTRACT}`,
  { label: 'wave3:decision-service', phase: 'Wave3-DecisionService' }
)
log('Wave 3 (decision-service) complete')

// ============================ WAVE 4 ============================
phase('Wave4-TestsVerify')
const wave4 = await agent(
  `Wave 4: TESTS + END-TO-END VERIFICATION. All modules are built. cd ${ROOT}.
1. Ensure full reactor builds: mvn -q clean package -DskipTests. Fix any cross-module integration/compile error
   you find (you may edit any module). Do NOT leave it broken.
2. Start DynamoDB Local: docker compose up -d dynamodb (wait until :8000 responds).
3. Run the ETL demo seed: java -jar etl/target/<the-etl-jar> --berka-dir data/raw/berka --ddb-endpoint http://localhost:8000 --demo
   (find the actual jar name). Confirm tables created + seeded (no error). It must finish under ~2 min.
4. Boot decision-service: java -jar decision-service/target/*.jar (background, DIAKRISIS_JWT_SECRET set to a >=32 char value,
   point dynamo to :8000, models-dir ${MODELS}). Wait for :8081. Boot bank-app similarly on :8080.
5. Write the GOLDEN-PATH TEST SUITE in decision-service/src/test (JUnit 5 + @SpringBootTest webEnvironment RANDOM_PORT +
   MockMvc or TestRestTemplate). Point it at a real DynamoDB Local (the running :8000 container) on a test table set;
   seed the needed feature rows in @BeforeEach (freeze the clock; T5a before T5b). One test per T1-T6 asserting the
   EXPECTED values in the CONTRACT (decision, band, scaExempt, typologies, signal presence, explanation null/non-null,
   combined==engine, latencyMs<50). Add the assertable contract-invariant tests (CI-1,4,7,8,9,11). NO @Disabled, NO stubs.
6. Run: mvn -q -pl decision-service test. ITERATE: if a verdict is wrong, FIX the cause (tune Weights/signal curves
   in engine, or the seed values in etl) until ALL T1-T6 pass. Re-run mvn install on engine/etl as needed after edits.
   Especially: T3 must land 30-59 (ensure A1 fires on account-wide robust-z); T6 HOLD must come from the Ty4 pin
   (typology sets band=HOLD); T5b raw ~84 must be HOLD not BLOCK (>=2 typologies BLOCK only when raw>=85).
7. ALSO do a live curl check: login via bank-app /auth/login -> token; POST /decision (or via bank-app /transfers,
   /deposits/break) for at least T1, T3, T5a, T5b and confirm the live JSON verdict+band match. Capture the responses.
8. Tear down background jars and the docker container at the end (docker compose down).
Report: a clear table of T1-T6 PASS/FAIL with the actual score+verdict each produced, which CI invariants pass,
the live curl outputs, and any case that needed tuning or remains blocked + why.
CONTRACT:
${CONTRACT}`,
  { label: 'wave4:tests-verify', phase: 'Wave4-TestsVerify' }
)

return {
  wave1: wave1?.slice(0, 4000),
  wave2_engine: wave2[0]?.slice(0, 2000),
  wave2_etl: wave2[1]?.slice(0, 2000),
  wave2_bankapp: wave2[2]?.slice(0, 2000),
  wave3: wave3?.slice(0, 2000),
  wave4: wave4,
}
