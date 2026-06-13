package com.cy.diakritis.etl;

import com.cy.diakritis.common.persistence.AccountStatsItem;
import com.cy.diakritis.common.persistence.CounterpartyBaselineItem;
import com.cy.diakritis.common.persistence.DynamoConfigSupport;
import com.cy.diakritis.common.persistence.DynamoProperties;
import com.cy.diakritis.common.persistence.TableBootstrap;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Berka feature-extraction ETL.
 *
 * <p>Streams {@code fin_trans.tsv} once (never loading it whole), aggregates outgoing ({@code ROB})
 * payments into per-(account, counterparty) baselines and per-account robust statistics, enriches
 * accounts with owner/disponent flags from {@code fin_disp.tsv}, provisions the feature tables, and
 * writes them to DynamoDB. With {@code --demo} it additionally writes the disclosed demonstration
 * seed (constructed items marked {@code source="CONSTRUCTED"}, real aggregates {@code "BERKA"}).
 *
 * <p>Arguments:
 * <ul>
 *   <li>{@code --berka-dir <dir>}    directory containing the Berka TSVs (default {@code ../data/raw/berka}).</li>
 *   <li>{@code --ddb-endpoint <url>} local DynamoDB endpoint (default {@code http://localhost:8000}).</li>
 *   <li>{@code --rebuild-features}   rebuild the real feature tables from the stream (default on).</li>
 *   <li>{@code --demo}               also write the demonstration seed.</li>
 * </ul>
 */
public final class BerkaEtl {

    private static final Logger LOG = LoggerFactory.getLogger(BerkaEtl.class);

    private static final String TRANSACTIONS_FILE = "fin_trans.tsv";
    private static final String DISPOSITIONS_FILE = "fin_disp.tsv";

    /**
     * Tab-delimited, headerless format. The Berka exports carry no header row, so {@code CSVFormat.TDF}
     * is used as-is (no header skipping) and fields are read positionally via {@link CSVRecord#get(int)}.
     * Empty trailing fields are preserved (a row may legitimately have no counterparty account).
     */
    private static final CSVFormat TSV_HEADERLESS = CSVFormat.TDF;

    private static final String OPERATION_ROB = "ROB";
    private static final String DISP_OWNER = "O";
    private static final String DISP_DISPONENT = "D";

    // Positional columns of fin_trans.tsv (headerless).
    private static final int TRANS_ACCOUNT_ID = 1;
    private static final int TRANS_DATE = 2;
    private static final int TRANS_AMOUNT = 3;
    private static final int TRANS_OPERATION = 6;
    private static final int TRANS_BANK = 8;
    private static final int TRANS_COUNTERPARTY_ACCOUNT = 9;
    private static final int TRANS_COLUMN_COUNT = 10;

    // Positional columns of fin_disp.tsv (headerless): disp_id, client_id, account_id, type.
    private static final int DISP_CLIENT_ID = 1;
    private static final int DISP_ACCOUNT_ID = 2;
    private static final int DISP_TYPE = 3;
    private static final int DISP_COLUMN_COUNT = 4;

    private BerkaEtl() {
    }

    public static void main(String[] args) throws IOException {
        Args parsed = Args.parse(args);
        LOG.info("Starting Berka ETL: berkaDir={}, endpoint={}, rebuildFeatures={}, demo={}",
                parsed.berkaDir(), parsed.ddbEndpoint(), parsed.rebuildFeatures(), parsed.demo());

        DynamoProperties props = new DynamoProperties();
        props.setEndpoint(parsed.ddbEndpoint());

        DynamoDbClient client = DynamoConfigSupport.client(props);
        DynamoDbEnhancedClient enhanced = DynamoConfigSupport.enhanced(client);

        TableBootstrap.createIfMissing(client, EtlSchemas.featureTables());
        if (parsed.demo()) {
            TableBootstrap.createIfMissing(client, EtlSchemas.demoTables());
        }

        Aggregates aggregates = streamAndAggregate(parsed.berkaDir());
        readDispositions(parsed.berkaDir(), aggregates);

        if (parsed.rebuildFeatures()) {
            writeFeatureTables(enhanced, aggregates);
        }

        if (parsed.demo()) {
            new DemoSeed(enhanced, Instant.now()).write(
                    aggregates.accountAggs(), aggregates.counterpartyAggsByAccount());
            LOG.info("Demo seed written for acc-A/acc-B/acc-C and demo principals");
        }

        client.close();
        LOG.info("Berka ETL complete");
    }

    /**
     * Single streaming pass over {@code fin_trans.tsv}: only {@code ROB} (outgoing transfer) rows
     * contribute. Rows lacking a counterparty account are skipped (cash/standing-order operations
     * without a payee key cannot form a counterparty baseline).
     */
    private static Aggregates streamAndAggregate(Path berkaDir) throws IOException {
        Path transactions = berkaDir.resolve(TRANSACTIONS_FILE);
        Map<String, AccountAgg> accountAggs = new HashMap<>();
        Map<String, Map<String, CounterpartyAgg>> counterpartyAggs = new HashMap<>();

        long rowCount = 0;
        long robCount = 0;
        try (Reader reader = Files.newBufferedReader(transactions, StandardCharsets.UTF_8);
             CSVParser parser = TSV_HEADERLESS.parse(reader)) {

            for (CSVRecord record : parser) {
                rowCount++;
                if (record.size() < TRANS_COLUMN_COUNT) {
                    continue;
                }
                String operation = record.get(TRANS_OPERATION).trim();
                if (!OPERATION_ROB.equals(operation)) {
                    continue;
                }
                String bank = record.get(TRANS_BANK).trim();
                String counterpartyAccount = record.get(TRANS_COUNTERPARTY_ACCOUNT).trim();
                if (bank.isEmpty() || counterpartyAccount.isEmpty()) {
                    continue;
                }

                String accountId = record.get(TRANS_ACCOUNT_ID).trim();
                long amountCents = Math.abs(Keys.eurToCents(record.get(TRANS_AMOUNT)));
                long epochMs = Keys.dateToEpochMs(record.get(TRANS_DATE));
                String cpKey = Keys.counterpartyKey(bank, counterpartyAccount);

                accountAggs.computeIfAbsent(accountId, AccountAgg::new).add(amountCents);

                counterpartyAggs
                        .computeIfAbsent(accountId, k -> new LinkedHashMap<>())
                        .computeIfAbsent(cpKey, k ->
                                new CounterpartyAgg(accountId, cpKey, bank, counterpartyAccount))
                        .add(amountCents, epochMs);
                robCount++;
            }
        }

        Map<String, List<CounterpartyAgg>> byAccount = new HashMap<>();
        for (Map.Entry<String, Map<String, CounterpartyAgg>> entry : counterpartyAggs.entrySet()) {
            byAccount.put(entry.getKey(), new ArrayList<>(entry.getValue().values()));
        }
        LOG.info("Streamed {} transaction rows; {} ROB rows across {} accounts",
                rowCount, robCount, accountAggs.size());
        return new Aggregates(accountAggs, byAccount);
    }

    /**
     * Enrich account stats with the owner/disponent structure. An account holding both an OWNER and
     * one or more DISPONENT dispositions is treated as dual-access: a business account with a
     * designated approver, the disponent client ids becoming the approver user ids.
     */
    private static void readDispositions(Path berkaDir, Aggregates aggregates) throws IOException {
        Path dispositions = berkaDir.resolve(DISPOSITIONS_FILE);
        Map<String, List<String>> disponentsByAccount = new HashMap<>();

        try (Reader reader = Files.newBufferedReader(dispositions, StandardCharsets.UTF_8);
             CSVParser parser = TSV_HEADERLESS.parse(reader)) {

            for (CSVRecord record : parser) {
                if (record.size() < DISP_COLUMN_COUNT) {
                    continue;
                }
                String type = record.get(DISP_TYPE).trim();
                if (!DISP_DISPONENT.equals(type)) {
                    continue;
                }
                String accountId = record.get(DISP_ACCOUNT_ID).trim();
                String clientId = record.get(DISP_CLIENT_ID).trim();
                disponentsByAccount
                        .computeIfAbsent(accountId, k -> new ArrayList<>())
                        .add(clientId);
            }
        }
        aggregates.setDisponentsByAccount(disponentsByAccount);
        LOG.info("Read dispositions: {} accounts have a designated disponent",
                disponentsByAccount.size());
    }

    private static void writeFeatureTables(DynamoDbEnhancedClient enhanced, Aggregates aggregates) {
        FeatureWriter writer = new FeatureWriter(enhanced);
        long baselineCount = 0;
        long statsCount = 0;

        for (Map.Entry<String, List<CounterpartyAgg>> entry
                : aggregates.counterpartyAggsByAccount().entrySet()) {
            for (CounterpartyAgg agg : entry.getValue()) {
                writer.putBaseline(toBaselineItem(agg.accountId(), agg));
                baselineCount++;
            }
        }

        for (AccountAgg accountAgg : aggregates.accountAggs().values()) {
            List<String> approvers = aggregates.disponentsFor(accountAgg.accountId());
            boolean hasApprover = !approvers.isEmpty();
            writer.putStats(toStatsItem(accountAgg.accountId(), accountAgg,
                    hasApprover, hasApprover, approvers));
            statsCount++;
        }

        writer.flush();
        LOG.info("Wrote {} counterparty baselines and {} account-stats rows",
                baselineCount, statsCount);
    }

    static CounterpartyBaselineItem toBaselineItem(String accountId, CounterpartyAgg agg) {
        CounterpartyBaselineItem item = new CounterpartyBaselineItem();
        item.setPk(Keys.accountPk(accountId));
        item.setSk(Keys.counterpartySk(agg.counterpartyKey()));
        item.setAccountId(accountId);
        item.setCounterpartyKey(agg.counterpartyKey());
        item.setCounterpartyIban(agg.counterpartyKey());
        item.setPayCount(agg.payCount());
        item.setMeanAmountCents(agg.meanAmountCents());
        item.setStdAmountCents(agg.stdAmountCents());
        item.setFirstSeenEpochMs(agg.firstSeenEpochMs());
        item.setLastSeenEpochMs(agg.lastSeenEpochMs());
        item.setRecentPayments(new ArrayList<>(agg.recentPayments()));
        item.setStandingOrder(agg.isStandingOrder());
        item.setSource(Keys.SOURCE_BERKA);
        return item;
    }

    static AccountStatsItem toStatsItem(String accountId, AccountAgg agg,
                                        boolean isBusiness, boolean hasApprover,
                                        List<String> approverUserIds) {
        AccountStatsItem item = new AccountStatsItem();
        item.setPk(Keys.accountPk(accountId));
        item.setSk(Keys.META_SK);
        item.setOutMeanAmountCents(agg.outMeanAmountCents());
        item.setOutStdAmountCents(agg.outStdAmountCents());
        item.setOutMedianAmountCents(agg.outMedianAmountCents());
        item.setOutMadAmountCents(agg.outMadAmountCents());
        item.setOutTxnCount(agg.outTxnCount());
        item.setBusinessAccount(isBusiness);
        item.setHasDesignatedApprover(hasApprover);
        item.setApproverUserIds(new ArrayList<>(approverUserIds));
        item.setSource(Keys.SOURCE_BERKA);
        return item;
    }

    /** In-memory aggregation result plus disposition enrichment. */
    private static final class Aggregates {

        private final Map<String, AccountAgg> accountAggs;
        private final Map<String, List<CounterpartyAgg>> counterpartyAggsByAccount;
        private Map<String, List<String>> disponentsByAccount = Map.of();

        Aggregates(Map<String, AccountAgg> accountAggs,
                   Map<String, List<CounterpartyAgg>> counterpartyAggsByAccount) {
            this.accountAggs = accountAggs;
            this.counterpartyAggsByAccount = counterpartyAggsByAccount;
        }

        Map<String, AccountAgg> accountAggs() {
            return accountAggs;
        }

        Map<String, List<CounterpartyAgg>> counterpartyAggsByAccount() {
            return counterpartyAggsByAccount;
        }

        void setDisponentsByAccount(Map<String, List<String>> disponentsByAccount) {
            this.disponentsByAccount = disponentsByAccount;
        }

        List<String> disponentsFor(String accountId) {
            return disponentsByAccount.getOrDefault(accountId, List.of());
        }
    }

    /** Parsed CLI arguments with contract defaults. */
    private record Args(Path berkaDir, String ddbEndpoint, boolean rebuildFeatures, boolean demo) {

        // data/ lives at the repo root (a sibling of diakritis/); the jar is normally run from
        // diakritis/, so the default reaches one level up. Override with --berka-dir for other layouts.
        private static final String DEFAULT_BERKA_DIR = "../data/raw/berka";
        private static final String DEFAULT_ENDPOINT = "http://localhost:8000";

        static Args parse(String[] args) {
            Path berkaDir = Path.of(DEFAULT_BERKA_DIR);
            String endpoint = DEFAULT_ENDPOINT;
            boolean demo = false;
            boolean rebuildFeatures = false;
            boolean rebuildFlagSeen = false;

            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--berka-dir" -> berkaDir = Path.of(requireValue(args, ++i, "--berka-dir"));
                    case "--ddb-endpoint" -> endpoint = requireValue(args, ++i, "--ddb-endpoint");
                    case "--demo" -> demo = true;
                    case "--rebuild-features" -> {
                        rebuildFeatures = true;
                        rebuildFlagSeen = true;
                    }
                    default -> throw new IllegalArgumentException("Unknown argument: " + args[i]);
                }
            }

            // Rebuilding the real feature tables is the default behaviour; --rebuild-features is an
            // explicit opt-in that lets a caller run a feature-only pass. If the flag is absent we
            // still rebuild (the common standalone invocation, with or without --demo).
            if (!rebuildFlagSeen) {
                rebuildFeatures = true;
            }
            return new Args(berkaDir, endpoint, rebuildFeatures, demo);
        }

        private static String requireValue(String[] args, int index, String flag) {
            if (index >= args.length) {
                throw new IllegalArgumentException("Missing value for " + flag);
            }
            return args[index];
        }
    }
}
