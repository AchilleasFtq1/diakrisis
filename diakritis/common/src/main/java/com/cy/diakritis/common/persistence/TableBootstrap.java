package com.cy.diakritis.common.persistence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.CreateTableRequest;
import software.amazon.awssdk.services.dynamodb.model.DescribeTableRequest;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.ResourceInUseException;
import software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;
import software.amazon.awssdk.services.dynamodb.waiters.DynamoDbWaiter;

import java.util.ArrayList;
import java.util.List;

/**
 * Idempotent table provisioning for local DynamoDB.
 *
 * <p>For each schema: DescribeTable; on {@link ResourceNotFoundException} create the table with
 * {@code PAY_PER_REQUEST} billing and wait until it reaches {@code ACTIVE}. Existing tables are
 * left untouched so repeated boots are safe.
 */
public final class TableBootstrap {

    private static final Logger LOG = LoggerFactory.getLogger(TableBootstrap.class);

    private TableBootstrap() {
    }

    public static void createIfMissing(DynamoDbClient client, List<TableSchema> schemas) {
        if (client == null) {
            throw new IllegalArgumentException("client must not be null");
        }
        if (schemas == null) {
            throw new IllegalArgumentException("schemas must not be null");
        }
        for (TableSchema schema : schemas) {
            createIfMissing(client, schema);
        }
    }

    private static void createIfMissing(DynamoDbClient client, TableSchema schema) {
        try {
            client.describeTable(DescribeTableRequest.builder()
                    .tableName(schema.tableName())
                    .build());
            LOG.debug("Table {} already exists; skipping create", schema.tableName());
        } catch (ResourceNotFoundException notFound) {
            create(client, schema);
        }
    }

    private static void create(DynamoDbClient client, TableSchema schema) {
        List<AttributeDefinition> attributes = new ArrayList<>();
        List<KeySchemaElement> keys = new ArrayList<>();

        attributes.add(AttributeDefinition.builder()
                .attributeName(schema.partitionKey())
                .attributeType(ScalarAttributeType.S)
                .build());
        keys.add(KeySchemaElement.builder()
                .attributeName(schema.partitionKey())
                .keyType(KeyType.HASH)
                .build());

        if (schema.sortKey() != null && !schema.sortKey().isBlank()) {
            attributes.add(AttributeDefinition.builder()
                    .attributeName(schema.sortKey())
                    .attributeType(ScalarAttributeType.S)
                    .build());
            keys.add(KeySchemaElement.builder()
                    .attributeName(schema.sortKey())
                    .keyType(KeyType.RANGE)
                    .build());
        }

        boolean created = true;
        try {
            client.createTable(CreateTableRequest.builder()
                    .tableName(schema.tableName())
                    .attributeDefinitions(attributes)
                    .keySchema(keys)
                    .billingMode(BillingMode.PAY_PER_REQUEST)
                    .build());
        } catch (ResourceInUseException alreadyCreated) {
            // Startup race: another concurrently-starting service (or the ETL seed) created this table
            // between our describeTable miss and now. That's fine — the table exists; just wait for it.
            // (DynamoDB-Local returns a 400 "Cannot create preexisting table" as ResourceInUseException.)
            created = false;
        }

        try (DynamoDbWaiter waiter = client.waiter()) {
            waiter.waitUntilTableExists(DescribeTableRequest.builder()
                    .tableName(schema.tableName())
                    .build());
        }
        if (created) {
            LOG.info("Created DynamoDB table {} (pk={}, sk={})",
                    schema.tableName(), schema.partitionKey(), schema.sortKey());
        } else {
            LOG.info("Table {} was created concurrently at startup; using the existing table", schema.tableName());
        }
    }
}
