package com.cy.diakritis.common.persistence;

/**
 * Minimal key schema description for create-if-missing bootstrapping. Both keys are strings
 * for every Diakrisis table; {@code sortKey} may be null for hash-only tables (none today, but
 * the shape keeps {@link TableBootstrap} general). {@code ttlAttribute} names the numeric
 * (epoch-seconds) attribute DynamoDB TTL should expire on; null for tables without TTL.
 */
public record TableSchema(String tableName, String partitionKey, String sortKey, String ttlAttribute) {

    public TableSchema {
        if (tableName == null || tableName.isBlank()) {
            throw new IllegalArgumentException("tableName must not be blank");
        }
        if (partitionKey == null || partitionKey.isBlank()) {
            throw new IllegalArgumentException("partitionKey must not be blank");
        }
    }

    public static TableSchema of(String tableName, String partitionKey, String sortKey) {
        return new TableSchema(tableName, partitionKey, sortKey, null);
    }

    public static TableSchema of(String tableName, String partitionKey, String sortKey, String ttlAttribute) {
        return new TableSchema(tableName, partitionKey, sortKey, ttlAttribute);
    }
}
