package com.cy.diakritis.etl;

import com.cy.diakritis.common.persistence.AccountStatsItem;
import com.cy.diakritis.common.persistence.CounterpartyBaselineItem;
import com.cy.diakritis.common.persistence.Tables;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.enhanced.dynamodb.model.BatchWriteItemEnhancedRequest;
import software.amazon.awssdk.enhanced.dynamodb.model.WriteBatch;

import java.util.ArrayList;
import java.util.List;

/**
 * Buffers feature items and flushes them to DynamoDB in batches of 25 (the DynamoDB
 * BatchWriteItem hard limit). Each put fully overwrites any existing item with the same key,
 * keeping the ETL idempotent across reruns.
 */
final class FeatureWriter {

    private static final int BATCH_LIMIT = 25;

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
        WriteBatch.Builder<CounterpartyBaselineItem> batch = WriteBatch.builder(CounterpartyBaselineItem.class)
                .mappedTableResource(baselineTable);
        for (CounterpartyBaselineItem item : baselineBuffer) {
            batch.addPutItem(item);
        }
        enhanced.batchWriteItem(BatchWriteItemEnhancedRequest.builder()
                .writeBatches(batch.build())
                .build());
        baselineBuffer.clear();
    }

    private void flushStats() {
        if (statsBuffer.isEmpty()) {
            return;
        }
        WriteBatch.Builder<AccountStatsItem> batch = WriteBatch.builder(AccountStatsItem.class)
                .mappedTableResource(statsTable);
        for (AccountStatsItem item : statsBuffer) {
            batch.addPutItem(item);
        }
        enhanced.batchWriteItem(BatchWriteItemEnhancedRequest.builder()
                .writeBatches(batch.build())
                .build());
        statsBuffer.clear();
    }
}
