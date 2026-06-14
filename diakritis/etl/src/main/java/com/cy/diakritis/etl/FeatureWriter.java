package com.cy.diakritis.etl;

import com.cy.diakritis.common.persistence.AccountStatsItem;
import com.cy.diakritis.common.persistence.CounterpartyBaselineItem;
import com.cy.diakritis.common.persistence.Tables;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.MappedTableResource;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.enhanced.dynamodb.model.BatchWriteItemEnhancedRequest;
import software.amazon.awssdk.enhanced.dynamodb.model.BatchWriteResult;
import software.amazon.awssdk.enhanced.dynamodb.model.WriteBatch;

import java.util.ArrayList;
import java.util.List;

/**
 * Buffers feature items and flushes them to DynamoDB in batches of 25 (the DynamoDB
 * BatchWriteItem hard limit). Each put fully overwrites any existing item with the same key,
 * keeping the ETL idempotent across reruns.
 *
 * <p>BatchWriteItem does NOT throw when individual items fail (throughput throttling, internal
 * server errors, item-size issues). It returns the failed puts in {@code UnprocessedItems}, which
 * the enhanced client surfaces via {@link BatchWriteResult#unprocessedPutItemsForTable}. Every flush
 * therefore drains those unprocessed puts in a bounded exponential-backoff retry loop and aborts the
 * ETL loudly if it cannot drain them — so the loader never reports false success while leaving the
 * feature tables incomplete (which would make the engine serve missing baselines as "never seen").
 */
final class FeatureWriter {

    private static final int BATCH_LIMIT = 25;

    /** How many drain attempts before we give up and abort the ETL. */
    private static final int MAX_DRAIN_ATTEMPTS = 5;

    /** Base backoff between drain attempts; doubles each attempt (50, 100, 200, 400 ms). */
    private static final long BASE_BACKOFF_MILLIS = 50L;

    private final DynamoDbTable<CounterpartyBaselineItem> baselineTable;
    private final DynamoDbTable<AccountStatsItem> statsTable;

    private final List<CounterpartyBaselineItem> baselineBuffer = new ArrayList<>(BATCH_LIMIT);
    private final List<AccountStatsItem> statsBuffer = new ArrayList<>(BATCH_LIMIT);

    private final DynamoDbEnhancedClient enhanced;

    FeatureWriter(DynamoDbEnhancedClient enhanced) {
        this.enhanced = enhanced;
        this.baselineTable = enhanced.table(Tables.COUNTERPARTY_BASELINE,
                TableSchema.fromBean(CounterpartyBaselineItem.class));
        this.statsTable = enhanced.table(Tables.ACCOUNT_STATS,
                TableSchema.fromBean(AccountStatsItem.class));
    }

    void putBaseline(CounterpartyBaselineItem item) {
        baselineBuffer.add(item);
        if (baselineBuffer.size() >= BATCH_LIMIT) {
            flushBaseline();
        }
    }

    void putStats(AccountStatsItem item) {
        statsBuffer.add(item);
        if (statsBuffer.size() >= BATCH_LIMIT) {
            flushStats();
        }
    }

    void flush() {
        flushBaseline();
        flushStats();
    }

    private void flushBaseline() {
        if (baselineBuffer.isEmpty()) {
            return;
        }
        writeAll(baselineTable, CounterpartyBaselineItem.class, baselineBuffer);
        baselineBuffer.clear();
    }

    private void flushStats() {
        if (statsBuffer.isEmpty()) {
            return;
        }
        writeAll(statsTable, AccountStatsItem.class, statsBuffer);
        statsBuffer.clear();
    }

    /**
     * Put every item in {@code items} into {@code table}, draining any DynamoDB-reported unprocessed
     * puts in a bounded exponential-backoff retry loop. Throws {@link IllegalStateException} if the
     * unprocessed set cannot be fully drained within {@link #MAX_DRAIN_ATTEMPTS} retries, so the ETL
     * aborts rather than logging false success with an incomplete feature table.
     */
    private <T> void writeAll(MappedTableResource<T> table, Class<T> itemClass, List<T> items) {
        List<T> pending = items;
        for (int attempt = 0; attempt <= MAX_DRAIN_ATTEMPTS; attempt++) {
            BatchWriteResult result = enhanced.batchWriteItem(buildRequest(table, itemClass, pending));
            List<T> unprocessed = result.unprocessedPutItemsForTable(table);
            if (unprocessed == null || unprocessed.isEmpty()) {
                return;
            }
            if (attempt == MAX_DRAIN_ATTEMPTS) {
                throw new IllegalStateException(String.format(
                        "DynamoDB BatchWriteItem left %d unprocessed put(s) for table %s after %d drain "
                                + "attempts; aborting ETL to avoid an incomplete feature table",
                        unprocessed.size(), table.tableName(), MAX_DRAIN_ATTEMPTS));
            }
            // Re-batch only the items DynamoDB could not process, after an exponential backoff.
            pending = new ArrayList<>(unprocessed);
            backoff(attempt);
        }
    }

    private <T> BatchWriteItemEnhancedRequest buildRequest(MappedTableResource<T> table,
                                                           Class<T> itemClass, List<T> items) {
        WriteBatch.Builder<T> batch = WriteBatch.builder(itemClass).mappedTableResource(table);
        for (T item : items) {
            batch.addPutItem(item);
        }
        return BatchWriteItemEnhancedRequest.builder()
                .writeBatches(batch.build())
                .build();
    }

    private static void backoff(int attempt) {
        long delayMillis = BASE_BACKOFF_MILLIS << attempt;
        try {
            Thread.sleep(delayMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while backing off to retry unprocessed puts", e);
        }
    }
}
