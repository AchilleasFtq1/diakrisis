package com.cy.diakritis.etl;

import com.cy.diakritis.common.persistence.AccountItem;
import com.cy.diakritis.common.persistence.AccountStatsItem;
import com.cy.diakritis.common.persistence.CounterpartyBaselineItem;
import com.cy.diakritis.common.persistence.CounterpartyByNameItem;
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

    private final DynamoDbEnhancedClient enhanced;
    private final DynamoDbTable<CounterpartyBaselineItem> baselineTable;
    private final DynamoDbTable<AccountStatsItem> statsTable;
    private final DynamoDbTable<CounterpartyByNameItem> byNameTable;
    private final DynamoDbTable<AccountItem> accountsTable;
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
    }

    void write(Map<String, AccountAgg> accountAggs,
               Map<String, List<CounterpartyAgg>> counterpartyAggsByAccount) {
        rekeyAggregates(accountAggs, counterpartyAggsByAccount);
        seedAccountA();
        seedAccountB();
        seedAccountC();
        seedAuthPrincipals();
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
