package com.cy.diakritis.etl;

import com.cy.diakritis.common.persistence.AccountItem;
import com.cy.diakritis.common.persistence.AccountPostureItem;
import com.cy.diakritis.common.persistence.AccountStatsItem;
import com.cy.diakritis.common.persistence.CounterpartyBaselineItem;
import com.cy.diakritis.common.persistence.CounterpartyByNameItem;
import com.cy.diakritis.common.persistence.CounterpartyReputationItem;
import com.cy.diakritis.common.persistence.ObservationItem;
import com.cy.diakritis.common.persistence.RecentPayment;
import com.cy.diakritis.common.persistence.Tables;
import com.cy.diakritis.common.persistence.TermDeposit;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Writes the demonstration seed described in the contract SEED section. The seed re-anchors all
 * age-sensitive timestamps to {@code now} so the demo scenarios (T1-T6) reproduce deterministically
 * no matter when the ETL is run.
 *
 * <p>It re-keys the real Berka aggregates for accounts 7819/8261/3834 onto the demo account ids
 * {@code acc-A}/{@code acc-B}/{@code acc-C}, then overlays the three disclosed constructed items:
 * the T4 Confirmation-of-Payee supplier name, the T5 term deposit, and the T6 escalation sequence.
 */
final class DemoSeed {

    /** Demo account id -> source Berka numeric account id. */
    private static final Map<String, String> ACCOUNT_MAPPINGS = Map.of(
            "acc-A", "7819",
            "acc-B", "8261",
            "acc-C", "3834");

    private static final String CUSTOMER_A = "customer-A";
    private static final String CUSTOMER_B = "customer-B";
    private static final String CUSTOMER_C = "customer-C";
    private static final String APPROVER_BIZ = "approver-biz";
    private static final String OPS_USER = "ops-user";

    // T4 constructed Confirmation-of-Payee supplier (the only constructed CoP name).
    private static final String T4_SUPPLIER_DISPLAY_NAME = "Meridian Supplies Ltd";
    private static final long T4_SUPPLIER_MEAN_CENTS = 150_000L;
    private static final long T4_SUPPLIER_PAY_COUNT = 5L;
    private static final String T4_ESTABLISHED_BANK = "QR";
    private static final String T4_ESTABLISHED_ACCOUNT = "55012077";

    // T5 constructed term deposit on acc-B.
    private static final String T5_DEPOSIT_ID = "dep-001";
    private static final long T5_PRINCIPAL_CENTS = 500_000L;
    private static final long T5_PENALTY_CENTS = 12_500L;
    private static final long ACC_B_BALANCE_CENTS = 498_000L;

    // T6 constructed escalation counterparty on acc-C.
    private static final String T6_BANK = "ZZ";
    private static final String T6_ACCOUNT = "90087712";

    // --- T7-T15 disclosed (CONSTRUCTED) scenario constants ---
    private static final long DAY_MS = 24L * 60L * 60L * 1000L;
    private static final String CUSTOMER_D = "customer-D";
    private static final String CUSTOMER_E = "customer-E";
    private static final String CUSTOMER_F = "customer-F";
    private static final String BIZ_INITIATOR = "biz-initiator";
    private static final String MULE_INITIATOR = "mule-initiator";
    private static final String CUSTOMER_X1 = "customer-x1-a";
    private static final String CUSTOMER_X2 = "customer-x1-b";

    // T7/T8 P2P alias.
    private static final String ACC_D = "acc-D";
    private static final String T7_MSISDN = "+35799123456";
    private static final String T7_GEORGE_REF = "P2P-GEORGE-ORIG";

    // T9 salami.
    private static final String ACC_E = "acc-E";
    private static final String T9_SALAMI_CP = "CY|SALAMI0001";

    // T10 stacked.
    private static final String ACC_F = "acc-F";

    // T11 payroll business account + the redirected line L02.
    private static final String BIZ_0042 = "biz-0042";
    private static final String L02_EMPLOYEE_NAME = "M. Ioannou";
    private static final String L02_OLD_IBAN = "CY|L02OLD";

    // T12 mule fan-out business account.
    private static final String BIZ_9999 = "biz-9999";

    // T15 cross-account counterparty (flagged on one account, seen on another).
    private static final String ACC_X1A = "acc-X1A";
    private static final String ACC_X1B = "acc-B2";
    private static final String CP_MULE = "CY|CPMULE99";

    // §17 vulnerability-aware friction: the single flagged-vulnerable demo account (retail, owner
    // customer-vuln). An ALLOW/CONFIRM-band action on it is escalated exactly one band (capped at
    // HOLD). It is intentionally NOT one of the T1-T15 demo accounts, so the golden-path assertions
    // are unaffected.
    private static final String ACC_V = "acc-V";
    private static final String CUSTOMER_VULN = "customer-vuln";
    private static final String V_ESTABLISHED_CP = "CY|VULNCP001";

    private final DynamoDbEnhancedClient enhanced;
    private final DynamoDbTable<CounterpartyBaselineItem> baselineTable;
    private final DynamoDbTable<AccountStatsItem> statsTable;
    private final DynamoDbTable<CounterpartyByNameItem> byNameTable;
    private final DynamoDbTable<AccountItem> accountsTable;
    private final DynamoDbTable<ObservationItem> observationTable;
    private final DynamoDbTable<AccountPostureItem> postureTable;
    private final DynamoDbTable<CounterpartyReputationItem> reputationTable;
    private final Instant now;

    DemoSeed(DynamoDbEnhancedClient enhanced, Instant now) {
        this.enhanced = enhanced;
        this.now = now;
        this.baselineTable = enhanced.table(Tables.COUNTERPARTY_BASELINE,
                TableSchema.fromBean(CounterpartyBaselineItem.class));
        this.statsTable = enhanced.table(Tables.ACCOUNT_STATS,
                TableSchema.fromBean(AccountStatsItem.class));
        this.byNameTable = enhanced.table(Tables.COUNTERPARTY_BY_NAME,
                TableSchema.fromBean(CounterpartyByNameItem.class));
        this.accountsTable = enhanced.table(Tables.ACCOUNTS,
                TableSchema.fromBean(AccountItem.class));
        this.observationTable = enhanced.table(Tables.OBSERVATIONS,
                TableSchema.fromBean(ObservationItem.class));
        this.postureTable = enhanced.table(Tables.ACCOUNT_POSTURE,
                TableSchema.fromBean(AccountPostureItem.class));
        this.reputationTable = enhanced.table(Tables.COUNTERPARTY_REPUTATION,
                TableSchema.fromBean(CounterpartyReputationItem.class));
    }

    void write(Map<String, AccountAgg> accountAggs,
               Map<String, List<CounterpartyAgg>> counterpartyAggsByAccount) {
        rekeyAggregates(accountAggs, counterpartyAggsByAccount);
        seedAccountA();
        seedAccountB();
        seedAccountC();
        seedAuthPrincipals();
        // T7-T15 scenarios (all CONSTRUCTED).
        seedP2pAliasAccount();       // T7 first-send + T8 re-point
        seedSalamiAccount();         // T9
        seedStackedAccount();        // T10
        seedPayrollAccount();        // T11
        seedMuleFanOutAccount();     // T12
        seedCrossAccountReputation();// T15
        seedVulnerableAccount();     // §17 vulnerability-aware friction
    }

    /**
     * Copy the real Berka feature rows for the three demo accounts onto their {@code acc-*} keys so
     * the engine reads established history (B4, A1/A3 baselines) under the demo identity.
     */
    private void rekeyAggregates(Map<String, AccountAgg> accountAggs,
                                 Map<String, List<CounterpartyAgg>> counterpartyAggsByAccount) {
        for (Map.Entry<String, String> mapping : ACCOUNT_MAPPINGS.entrySet()) {
            String demoAccountId = mapping.getKey();
            String berkaAccountId = mapping.getValue();

            AccountAgg accountAgg = accountAggs.get(berkaAccountId);
            if (accountAgg != null) {
                statsTable.putItem(BerkaEtl.toStatsItem(demoAccountId, accountAgg,
                        false, false, List.of()));
            }

            List<CounterpartyAgg> counterparties = counterpartyAggsByAccount.getOrDefault(
                    berkaAccountId, List.of());
            for (CounterpartyAgg agg : counterparties) {
                baselineTable.putItem(BerkaEtl.toBaselineItem(demoAccountId, agg));
            }
        }
    }

    /** acc-A: retail. Adds the T4 Confirmation-of-Payee supplier (constructed). */
    private void seedAccountA() {
        CounterpartyByNameItem cop = new CounterpartyByNameItem();
        cop.setPk(Keys.accountPk("acc-A"));
        cop.setSk(Keys.nameSk(Keys.normalizeName(T4_SUPPLIER_DISPLAY_NAME)));
        cop.setNormalizedName(Keys.normalizeName(T4_SUPPLIER_DISPLAY_NAME));
        cop.setDisplayName(T4_SUPPLIER_DISPLAY_NAME);
        String establishedKey = Keys.counterpartyKey(T4_ESTABLISHED_BANK, T4_ESTABLISHED_ACCOUNT);
        cop.setEstablishedIban(establishedKey);
        cop.setEstablishedCounterpartyKey(establishedKey);
        cop.setPayCount(T4_SUPPLIER_PAY_COUNT);
        cop.setMeanAmountCents(T4_SUPPLIER_MEAN_CENTS);
        cop.setFirstSeenEpochMs(epochMs(now.minus(Duration.ofDays(120))));
        cop.setLastSeenEpochMs(epochMs(now.minus(Duration.ofDays(2))));
        cop.setSource(Keys.SOURCE_CONSTRUCTED);
        byNameTable.putItem(cop);

        AccountItem account = baseAccount("acc-A", "Demo Retail Account A", CUSTOMER_A,
                fetchBalanceOrDefault("acc-A", 450_000L), false, List.of());
        account.setSource(Keys.SOURCE_BERKA);
        accountsTable.putItem(account);
    }

    /** acc-B: holds the constructed T5 term deposit. */
    private void seedAccountB() {
        TermDeposit deposit = new TermDeposit(
                T5_DEPOSIT_ID,
                T5_PRINCIPAL_CENTS,
                epochMs(now.plus(Duration.ofDays(180))),
                T5_PENALTY_CENTS,
                false);

        // Established home-device / country / platform behavioural baseline (aged 180d, so D1≈0 and
        // GEO=CY keeps G1 silent on the customer's own device). Without it, the T5a deposit break
        // would establish acc-B's device "moments" before the T5b sweep and the sweep would read its
        // own just-seen device as fresh (D1=1.0) — corroborating the safe-account typology and pushing
        // the kill-chain HOLD up to a two-typology BLOCK. The home baseline keeps the T5b liquidation
        // sweep a clean single-typology HOLD, matching the disclosed T5b golden path. The device id
        // (dev-b) and home range (203.0.113.x → CY) are what the demo's acc-B session presents.
        seedHomeDevice("acc-B", "dev-b", "IOS");

        AccountItem account = baseAccount("acc-B", "Demo Account B", CUSTOMER_B,
                ACC_B_BALANCE_CENTS, false, List.of());
        account.setTermDeposits(new ArrayList<>(List.of(deposit)));
        account.setSource(Keys.SOURCE_CONSTRUCTED);
        accountsTable.putItem(account);
    }

    /** acc-C: dual-access; carries the constructed T6 rising-payment escalation baseline. */
    private void seedAccountC() {
        CounterpartyBaselineItem escalation = new CounterpartyBaselineItem();
        String cpKey = Keys.counterpartyKey(T6_BANK, T6_ACCOUNT);
        escalation.setPk(Keys.accountPk("acc-C"));
        escalation.setSk(Keys.counterpartySk(cpKey));
        escalation.setAccountId("acc-C");
        escalation.setCounterpartyKey(cpKey);
        escalation.setCounterpartyIban(cpKey);
        escalation.setResolvedName("Online Companion");
        escalation.setPayCount(4L);
        escalation.setMeanAmountCents(62_500L);
        escalation.setStdAmountCents(38_000L);
        escalation.setFirstSeenEpochMs(epochMs(now.minus(Duration.ofDays(14))));
        escalation.setLastSeenEpochMs(epochMs(now.minus(Duration.ofDays(2))));
        escalation.setRecentPayments(new ArrayList<>(List.of(
                new RecentPayment(20_000L, epochMs(now.minus(Duration.ofDays(14)))),
                new RecentPayment(40_000L, epochMs(now.minus(Duration.ofDays(11)))),
                new RecentPayment(70_000L, epochMs(now.minus(Duration.ofDays(6)))),
                new RecentPayment(120_000L, epochMs(now.minus(Duration.ofDays(2)))))));
        escalation.setStandingOrder(false);
        escalation.setSource(Keys.SOURCE_CONSTRUCTED);
        baselineTable.putItem(escalation);

        AccountItem account = baseAccount("acc-C", "Demo Account C", CUSTOMER_C,
                fetchBalanceOrDefault("acc-C", 380_000L), true, List.of(APPROVER_BIZ));
        account.setSource(Keys.SOURCE_BERKA);
        accountsTable.putItem(account);
    }

    /**
     * Persist the approver and ops principals as lightweight account rows so bank-app
     * {@code /auth/login} can resolve them. The customer principals are already persisted as the
     * owners of acc-A/B/C.
     */
    private void seedAuthPrincipals() {
        AccountItem approver = baseAccount(APPROVER_BIZ, "APPROVER", APPROVER_BIZ, 0L, true, List.of());
        approver.setSource(Keys.SOURCE_CONSTRUCTED);
        accountsTable.putItem(approver);

        AccountItem ops = baseAccount(OPS_USER, "OPS", OPS_USER, 0L, false, List.of());
        ops.setSource(Keys.SOURCE_CONSTRUCTED);
        accountsTable.putItem(ops);
    }

    // ---------------------------------------------------------------------------------------------
    // T7-T15 disclosed scenarios (all CONSTRUCTED). Each seeds the feature/behavioural baselines the
    // engine reads so the demo's next live action reproduces the golden-path outcome.
    // ---------------------------------------------------------------------------------------------

    /**
     * acc-D — P2P alias (T7 first-send CONFIRM + CoP, T8 re-point HOLD). A thin retail account with no
     * outgoing baseline. The alias {@value #T7_MSISDN} is pre-resolved to George's original account in
     * the observation store, so a later send that resolves the SAME alias to a DIFFERENT account trips
     * P1 (the SIM-swap re-point). The established home device keeps D1/G1 quiet on the legitimate send.
     */
    private void seedP2pAliasAccount() {
        putStats(ACC_D, 0L, 0L, 0L, 0L, 0L, false, false);
        seedHomeDevice(ACC_D, "dev-d", "IOS");
        seedAliasResolution(ACC_D, T7_MSISDN, T7_GEORGE_REF);
        AccountItem account = baseAccount(ACC_D, "Demo P2P Account D", CUSTOMER_D, 120_000L, false, List.of());
        account.setSource(Keys.SOURCE_CONSTRUCTED);
        accountsTable.putItem(account);
    }

    /**
     * acc-E — salami / structuring (T9). A tight low-mean outgoing distribution plus an established
     * counterparty with FLAT recent payments (so V2 stays silent — this is pure slicing, not romance).
     * Three rapid €1,000 slices to this counterparty accumulate past the HOLD band on the third.
     */
    private void seedSalamiAccount() {
        putStats(ACC_E, 60_000L, 5_000L, 60_000L, 3_000L, 200L, false, false);
        seedHomeDevice(ACC_E, "dev-e", "IOS");
        long firstSeen = epochMs(now.minus(Duration.ofDays(8)));
        CounterpartyBaselineItem cp = baseline(ACC_E, T9_SALAMI_CP, 6L, 90_000L, firstSeen, List.of(
                new RecentPayment(90_000L, epochMs(now.minus(Duration.ofDays(8)))),
                new RecentPayment(90_000L, epochMs(now.minus(Duration.ofDays(6)))),
                new RecentPayment(90_000L, epochMs(now.minus(Duration.ofDays(4))))));
        cp.setResolvedName("Salami Payee");
        baselineTable.putItem(cp);
        AccountItem account = baseAccount(ACC_E, "Demo Account E", CUSTOMER_E, 320_000L, false, List.of());
        account.setSource(Keys.SOURCE_CONSTRUCTED);
        accountsTable.putItem(account);
    }

    /**
     * acc-F — stacked-signal liquidation (T10 BLOCK). A tight outgoing baseline, an established home
     * device / country / platform behavioural baseline (so a foreign-IP, new-device sweep trips G1/D1),
     * and a freshly-freed-funds posture (so K1 fires). The live demo sweep to a brand-new payee then
     * fires both the kill-chain and safe-account typologies at a raw score ≥ 85 → BLOCK.
     */
    private void seedStackedAccount() {
        putStats(ACC_F, 12_000L, 2_000L, 12_000L, 1_500L, 287L, false, false);
        seedHomeDevice(ACC_F, "dev-f", "WEB");
        seedFreedFundsPosture(ACC_F, 1_000_000L);
        AccountItem account = baseAccount(ACC_F, "Demo Account F", CUSTOMER_F, 1_000_000L, false, List.of());
        account.setSource(Keys.SOURCE_CONSTRUCTED);
        accountsTable.putItem(account);
    }

    /**
     * biz-0042 — payroll business account (T11 REQUIRE_APPROVAL + L02 quarantine). Business with a
     * designated approver and an established outgoing batch baseline. 49 employees (L01, L03..L50) have
     * prior payment history (clean lines); L02's employee {@value #L02_EMPLOYEE_NAME} is established
     * under an OLD IBAN by name, so the changed-IBAN line trips B5 and is quarantined.
     */
    private void seedPayrollAccount() {
        putStatsWithApprover(BIZ_0042, 140_000L, 20_000L, 140_000L, 20_000L, 300L, List.of(APPROVER_BIZ));
        for (int i = 1; i <= 50; i++) {
            if (i == 2) {
                continue; // L02 is the redirected line (no baseline on its new IBAN).
            }
            String iban = payrollIban(i);
            CounterpartyBaselineItem cp = baseline(BIZ_0042, iban, 12L, 140_000L,
                    epochMs(now.minus(Duration.ofDays(200))), List.of(
                            new RecentPayment(140_000L, epochMs(now.minus(Duration.ofDays(60)))),
                            new RecentPayment(140_000L, epochMs(now.minus(Duration.ofDays(30))))));
            cp.setResolvedName("Employee " + i);
            baselineTable.putItem(cp);
        }
        CounterpartyByNameItem l02 = new CounterpartyByNameItem();
        l02.setPk(Keys.accountPk(BIZ_0042));
        l02.setSk(Keys.nameSk(Keys.normalizeName(L02_EMPLOYEE_NAME)));
        l02.setNormalizedName(Keys.normalizeName(L02_EMPLOYEE_NAME));
        l02.setDisplayName(L02_EMPLOYEE_NAME);
        l02.setEstablishedIban(L02_OLD_IBAN);
        l02.setEstablishedCounterpartyKey(L02_OLD_IBAN);
        l02.setPayCount(12L);
        l02.setMeanAmountCents(138_000L);
        l02.setFirstSeenEpochMs(epochMs(now.minus(Duration.ofDays(200))));
        l02.setLastSeenEpochMs(epochMs(now.minus(Duration.ofDays(30))));
        l02.setSource(Keys.SOURCE_CONSTRUCTED);
        byNameTable.putItem(l02);
        AccountItem account = baseAccount(BIZ_0042, "Demo Payroll Business biz-0042", BIZ_INITIATOR,
                9_120_000L, true, List.of(APPROVER_BIZ));
        account.setSource(Keys.SOURCE_CONSTRUCTED);
        accountsTable.putItem(account);
    }

    /**
     * biz-9999 — mule fan-out account (T12 BLOCK). Retail (so a batch is not force-routed to corporate
     * approval) but with a small, tight outgoing baseline so the outsized 30-line fan-out total trips
     * MP2 alongside MP1/MP4 — pushing the worst line over the BLOCK escalation threshold.
     */
    private void seedMuleFanOutAccount() {
        putStats(BIZ_9999, 30_000L, 5_000L, 30_000L, 3_000L, 120L, false, false);
        AccountItem account = baseAccount(BIZ_9999, "Demo Mule Account biz-9999", MULE_INITIATOR,
                8_700_000L, false, List.of());
        account.setSource(Keys.SOURCE_CONSTRUCTED);
        accountsTable.putItem(account);
    }

    /**
     * The T15 cross-account moat pair. Account {@value #ACC_X1A} flags the shared counterparty
     * {@value #CP_MULE} (the seed pre-writes the reputation flag with worst outcome BLOCK, as a prior
     * liquidation would have), and account {@value #ACC_X1B} is a thin account whose otherwise-clean
     * payment to the SAME counterparty fires X1 → HOLD within the decay window.
     */
    private void seedCrossAccountReputation() {
        putStats(ACC_X1A, 12_000L, 2_000L, 12_000L, 1_500L, 287L, false, false);
        putStats(ACC_X1B, 0L, 0L, 0L, 0L, 0L, false, false);
        seedHomeDevice(ACC_X1B, "dev-b2", "WEB");
        // CP-MULE flagged moments ago with worst outcome BLOCK (full X1 severity) within the 6h window.
        flagReputation(CP_MULE, epochMs(now.minus(Duration.ofMinutes(1))), "BLOCK");

        AccountItem a = baseAccount(ACC_X1A, "Demo X-Account A", CUSTOMER_X1, 1_000_000L, false, List.of());
        a.setSource(Keys.SOURCE_CONSTRUCTED);
        accountsTable.putItem(a);
        AccountItem b = baseAccount(ACC_X1B, "Demo X-Account B2", CUSTOMER_X2, 500_000L, false, List.of());
        b.setSource(Keys.SOURCE_CONSTRUCTED);
        accountsTable.putItem(b);
    }

    /**
     * §17 — the single flagged-vulnerable demo account (acc-V, retail, owner customer-vuln). It is
     * given a tight low-mean outgoing distribution and one established counterparty so an everyday
     * anomalous-amount payment lands in the CONFIRM band — which the §17 friction then escalates one
     * band to HOLD because the account is flagged vulnerable. The established home device keeps the
     * device/geo signals quiet so the escalation is driven by the vulnerability flag, not by other
     * risk. Crucially this is NOT a T1-T15 account, so the golden-path assertions are unaffected.
     */
    private void seedVulnerableAccount() {
        putStatsVulnerable(ACC_V, 12_633L, 2_000L, 12_633L, 1_500L, 287L);
        seedHomeDevice(ACC_V, "dev-v", "IOS");

        long firstSeen = epochMs(now.minus(Duration.ofDays(200)));
        CounterpartyBaselineItem cp = baseline(ACC_V, V_ESTABLISHED_CP, 60L, 12_960L, firstSeen, List.of(
                new RecentPayment(12_960L, firstSeen),
                new RecentPayment(12_960L, epochMs(now.minus(Duration.ofDays(60)))),
                new RecentPayment(12_960L, epochMs(now.minus(Duration.ofDays(30))))));
        baselineTable.putItem(cp);

        AccountItem account = baseAccount(ACC_V, "Demo Vulnerable Account V", CUSTOMER_VULN,
                450_000L, false, List.of());
        account.setSource(Keys.SOURCE_CONSTRUCTED);
        accountsTable.putItem(account);
    }

    /** As {@link #putStats} but flags the account vulnerable for the §17 friction escalation. */
    private void putStatsVulnerable(String accountId, long mean, long std, long median, long mad, long count) {
        AccountStatsItem item = new AccountStatsItem();
        item.setPk(Keys.accountPk(accountId));
        item.setSk(Keys.META_SK);
        item.setOutMeanAmountCents(mean);
        item.setOutStdAmountCents(std);
        item.setOutMedianAmountCents(median);
        item.setOutMadAmountCents(mad);
        item.setOutTxnCount(count);
        item.setBusinessAccount(false);
        item.setHasDesignatedApprover(false);
        item.setApproverUserIds(List.of());
        item.setVulnerable(true);
        item.setSource(Keys.SOURCE_CONSTRUCTED);
        statsTable.putItem(item);
    }

    // --- T7-T15 seed helpers -----------------------------------------------------------------------

    private String payrollIban(int line) {
        return "CY|EMP" + String.format("%03d", line);
    }

    private void putStats(String accountId, long mean, long std, long median, long mad, long count,
                          boolean business, boolean approver) {
        AccountStatsItem item = new AccountStatsItem();
        item.setPk(Keys.accountPk(accountId));
        item.setSk(Keys.META_SK);
        item.setOutMeanAmountCents(mean);
        item.setOutStdAmountCents(std);
        item.setOutMedianAmountCents(median);
        item.setOutMadAmountCents(mad);
        item.setOutTxnCount(count);
        item.setBusinessAccount(business);
        item.setHasDesignatedApprover(approver);
        item.setApproverUserIds(List.of());
        item.setSource(Keys.SOURCE_CONSTRUCTED);
        statsTable.putItem(item);
    }

    private void putStatsWithApprover(String accountId, long mean, long std, long median, long mad,
                                      long count, List<String> approverUserIds) {
        AccountStatsItem item = new AccountStatsItem();
        item.setPk(Keys.accountPk(accountId));
        item.setSk(Keys.META_SK);
        item.setOutMeanAmountCents(mean);
        item.setOutStdAmountCents(std);
        item.setOutMedianAmountCents(median);
        item.setOutMadAmountCents(mad);
        item.setOutTxnCount(count);
        item.setBusinessAccount(true);
        item.setHasDesignatedApprover(true);
        item.setApproverUserIds(new ArrayList<>(approverUserIds));
        item.setSource(Keys.SOURCE_CONSTRUCTED);
        statsTable.putItem(item);
    }

    private CounterpartyBaselineItem baseline(String accountId, String cpKey, long payCount, long meanCents,
                                              long firstSeenMs, List<RecentPayment> recent) {
        CounterpartyBaselineItem item = new CounterpartyBaselineItem();
        item.setPk(Keys.accountPk(accountId));
        item.setSk(Keys.counterpartySk(cpKey));
        item.setAccountId(accountId);
        item.setCounterpartyKey(cpKey);
        item.setCounterpartyIban(cpKey);
        item.setPayCount(payCount);
        item.setMeanAmountCents(meanCents);
        item.setStdAmountCents(Math.max(1L, meanCents / 10));
        item.setFirstSeenEpochMs(firstSeenMs);
        item.setLastSeenEpochMs(epochMs(now.minus(Duration.ofDays(1))));
        item.setRecentPayments(new ArrayList<>(recent));
        item.setStandingOrder(false);
        item.setSource(Keys.SOURCE_CONSTRUCTED);
        return item;
    }

    /** Seed the established home device / country / platform / network behavioural baseline (D1≈0). */
    private void seedHomeDevice(String accountId, String deviceId, String platform) {
        putObservation(accountId, "DEVICE", deviceId, null);
        putObservation(accountId, "PLATFORM", platform, null);
        putObservation(accountId, "GEO", "CY", null);
        putObservation(accountId, "NETWORK", "203.0.113", null);
    }

    /** Seed an alias→account resolution (the prior P2P resolution P1 compares a re-point against). */
    private void seedAliasResolution(String accountId, String alias, String resolvedAccountRef) {
        putObservation(accountId, "ALIAS", alias, resolvedAccountRef);
    }

    private void putObservation(String accountId, String kind, String value, String resolvedRef) {
        ObservationItem item = new ObservationItem();
        item.setPk("OBS#" + accountId);
        item.setSk(kind + "#" + value);
        item.setAccountId(accountId);
        item.setKind(kind);
        item.setValue(value);
        item.setFirstSeenEpochMs(epochMs(now.minus(Duration.ofDays(180))));
        item.setLastSeenEpochMs(epochMs(now.minus(Duration.ofDays(1))));
        if (resolvedRef != null) {
            item.setLastResolvedAccountRef(resolvedRef);
        }
        item.setTtlEpochSec((epochMs(now) + 72L * 60L * 60L * 1000L) / 1000L);
        observationTable.putItem(item);
    }

    private void seedFreedFundsPosture(String accountId, long freedCents) {
        // The posture row is optimistic-locked (@DynamoDbVersionAttribute). Re-seeding onto an existing
        // DynamoDB volume (a stack `up` without `down -v`) must carry the current version, otherwise the
        // blind putItem's version condition (attribute_not_exists / version match) is rejected and the
        // seeder aborts. Reading the existing row first makes the seed idempotent across re-runs.
        AccountPostureItem existing = postureTable.getItem(
                software.amazon.awssdk.enhanced.dynamodb.Key.builder()
                        .partitionValue(Keys.accountPk(accountId)).sortValue("POSTURE").build());
        AccountPostureItem item = new AccountPostureItem();
        item.setPk(Keys.accountPk(accountId));
        item.setSk("POSTURE");
        item.setFundsFreedEur72hCents(freedCents);
        item.setLastDepositBreakEpochMs(epochMs(now.minus(Duration.ofMinutes(1))));
        item.setTtlEpochSec((epochMs(now) + 72L * 60L * 60L * 1000L) / 1000L);
        if (existing != null) {
            item.setVersion(existing.getVersion());
            item.setAppliedEventIds(existing.getAppliedEventIds());
        }
        postureTable.putItem(item);
    }

    private void flagReputation(String cpKey, long lastFlagEpochMs, String worstOutcome) {
        CounterpartyReputationItem item = new CounterpartyReputationItem();
        item.setPk("CP#" + cpKey);
        item.setSk("REP");
        item.setCounterpartyKey(cpKey);
        item.setLastFlagEpochMs(lastFlagEpochMs);
        item.setWorstOutcome(worstOutcome);
        item.setFlagCount(1L);
        item.setTtlEpochSec((epochMs(now) + 90L * DAY_MS) / 1000L);
        reputationTable.putItem(item);
    }

    private AccountItem baseAccount(String accountId, String displayName, String ownerUserId,
                                    long balanceCents, boolean isBusiness, List<String> approvers) {
        AccountItem account = new AccountItem();
        account.setPk(Keys.accountPk(accountId));
        account.setSk(Keys.META_SK);
        account.setDisplayName(displayName);
        account.setOwnerUserId(ownerUserId);
        account.setAvailableBalanceCents(balanceCents);
        account.setBusiness(isBusiness);
        account.setApproverUserIds(new ArrayList<>(approvers));
        account.setTermDeposits(new ArrayList<>());
        return account;
    }

    /** Reuse an already-written balance if a prior demo run persisted one; else fall back. */
    private long fetchBalanceOrDefault(String accountId, long fallbackCents) {
        try {
            AccountItem existing = accountsTable.getItem(Key.builder()
                    .partitionValue(Keys.accountPk(accountId))
                    .sortValue(Keys.META_SK)
                    .build());
            if (existing != null && existing.getAvailableBalanceCents() > 0) {
                return existing.getAvailableBalanceCents();
            }
        } catch (ResourceNotFoundException ignored) {
            // table not yet populated; use the fallback below
        }
        return fallbackCents;
    }

    private static long epochMs(Instant instant) {
        return instant.toEpochMilli();
    }
}
