package com.cy.diakritis.ops.config;

import com.cy.diakritis.common.persistence.AccountPostureItem;
import com.cy.diakritis.common.persistence.CaseItem;
import com.cy.diakritis.common.persistence.CounterpartyBaselineItem;
import com.cy.diakritis.common.persistence.CounterpartyReputationItem;
import com.cy.diakritis.common.persistence.DecisionItem;
import com.cy.diakritis.common.persistence.DynamoConfigSupport;
import com.cy.diakritis.common.persistence.DynamoProperties;
import com.cy.diakritis.common.persistence.ObservationItem;
import com.cy.diakritis.common.persistence.OutcomeItem;
import com.cy.diakritis.common.persistence.TableBootstrap;
import com.cy.diakritis.common.persistence.TableSchema;
import com.cy.diakritis.common.persistence.Tables;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

import java.util.List;

/**
 * Wires the AWS SDK v2 DynamoDB clients and the typed tables ops-service reads ({@code Decisions},
 * {@code Cases}, {@code Outcomes}). These tables are owned (written) by decision-service; ops-service
 * is a read-side consumer. When {@code diakrisis.dynamo.auto-create} is enabled the tables are
 * provisioned at startup so a fresh local DynamoDB boots cleanly regardless of which service first
 * touches them.
 */
@Configuration
public class DynamoConfig {

    @Bean
    DynamoDbClient dynamoDbClient(DynamoProperties properties) {
        DynamoDbClient client = DynamoConfigSupport.client(properties);
        if (properties.isAutoCreate()) {
            TableBootstrap.createIfMissing(client, List.of(
                    TableSchema.of(Tables.DECISIONS, "pk", "sk"),
                    TableSchema.of(Tables.CASES, "pk", "sk"),
                    TableSchema.of(Tables.OUTCOMES, "pk", "sk"),
                    TableSchema.of(Tables.ACCOUNT_POSTURE, "pk", "sk", "ttlEpochSec"),
                    TableSchema.of(Tables.OBSERVATIONS, "pk", "sk", "ttlEpochSec"),
                    TableSchema.of(Tables.COUNTERPARTY_REPUTATION, "pk", "sk", "ttlEpochSec"),
                    TableSchema.of(Tables.COUNTERPARTY_BASELINE, "pk", "sk")));
        }
        return client;
    }

    @Bean
    DynamoDbEnhancedClient dynamoDbEnhancedClient(DynamoDbClient client) {
        return DynamoConfigSupport.enhanced(client);
    }

    @Bean
    DynamoDbTable<DecisionItem> decisionTable(DynamoDbEnhancedClient enhanced) {
        return enhanced.table(Tables.DECISIONS,
                software.amazon.awssdk.enhanced.dynamodb.TableSchema.fromBean(DecisionItem.class));
    }

    @Bean
    DynamoDbTable<CaseItem> caseTable(DynamoDbEnhancedClient enhanced) {
        return enhanced.table(Tables.CASES,
                software.amazon.awssdk.enhanced.dynamodb.TableSchema.fromBean(CaseItem.class));
    }

    @Bean
    DynamoDbTable<OutcomeItem> outcomeTable(DynamoDbEnhancedClient enhanced) {
        return enhanced.table(Tables.OUTCOMES,
                software.amazon.awssdk.enhanced.dynamodb.TableSchema.fromBean(OutcomeItem.class));
    }

    @Bean
    DynamoDbTable<AccountPostureItem> accountPostureTable(DynamoDbEnhancedClient enhanced) {
        return enhanced.table(Tables.ACCOUNT_POSTURE,
                software.amazon.awssdk.enhanced.dynamodb.TableSchema.fromBean(AccountPostureItem.class));
    }

    @Bean
    DynamoDbTable<ObservationItem> observationTable(DynamoDbEnhancedClient enhanced) {
        return enhanced.table(Tables.OBSERVATIONS,
                software.amazon.awssdk.enhanced.dynamodb.TableSchema.fromBean(ObservationItem.class));
    }

    @Bean
    DynamoDbTable<CounterpartyReputationItem> reputationTable(DynamoDbEnhancedClient enhanced) {
        return enhanced.table(Tables.COUNTERPARTY_REPUTATION,
                software.amazon.awssdk.enhanced.dynamodb.TableSchema.fromBean(CounterpartyReputationItem.class));
    }

    @Bean
    DynamoDbTable<CounterpartyBaselineItem> baselineTable(DynamoDbEnhancedClient enhanced) {
        return enhanced.table(Tables.COUNTERPARTY_BASELINE,
                software.amazon.awssdk.enhanced.dynamodb.TableSchema.fromBean(CounterpartyBaselineItem.class));
    }
}
