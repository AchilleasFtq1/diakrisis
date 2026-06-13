package com.cy.diakritis.decision.config;

import com.cy.diakritis.common.persistence.AccountItem;
import com.cy.diakritis.common.persistence.AccountPostureItem;
import com.cy.diakritis.common.persistence.AccountStatsItem;
import com.cy.diakritis.common.persistence.CaseItem;
import com.cy.diakritis.common.persistence.CounterpartyBaselineItem;
import com.cy.diakritis.common.persistence.CounterpartyByNameItem;
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
 * Wires the AWS SDK v2 DynamoDB clients and the typed tables the decision service reads (offline
 * feature baselines) and writes (decisions, posture, observations, reputation, cases). When
 * {@code diakrisis.dynamo.auto-create} is enabled every required table is provisioned at startup so
 * a fresh local DynamoDB boots cleanly — the {@code TableBootstrap} on startup the contract calls for.
 */
@Configuration
public class DynamoConfig {

    private static final String PK = "pk";
    private static final String SK = "sk";

    @Bean
    DynamoDbClient dynamoDbClient(DynamoProperties properties) {
        DynamoDbClient client = DynamoConfigSupport.client(properties);
        if (properties.isAutoCreate()) {
            TableBootstrap.createIfMissing(client, List.of(
                    TableSchema.of(Tables.COUNTERPARTY_BASELINE, PK, SK),
                    TableSchema.of(Tables.ACCOUNT_STATS, PK, SK),
                    TableSchema.of(Tables.COUNTERPARTY_BY_NAME, PK, SK),
                    TableSchema.of(Tables.DECISIONS, PK, SK),
                    TableSchema.of(Tables.OBSERVATIONS, PK, SK),
                    TableSchema.of(Tables.ACCOUNT_POSTURE, PK, SK),
                    TableSchema.of(Tables.COUNTERPARTY_REPUTATION, PK, SK),
                    TableSchema.of(Tables.ACCOUNTS, PK, SK),
                    TableSchema.of(Tables.CASES, PK, SK),
                    TableSchema.of(Tables.OUTCOMES, PK, SK)));
        }
        return client;
    }

    @Bean
    DynamoDbEnhancedClient dynamoDbEnhancedClient(DynamoDbClient client) {
        return DynamoConfigSupport.enhanced(client);
    }

    @Bean
    DynamoDbTable<CounterpartyBaselineItem> counterpartyBaselineTable(DynamoDbEnhancedClient enhanced) {
        return enhanced.table(Tables.COUNTERPARTY_BASELINE,
                software.amazon.awssdk.enhanced.dynamodb.TableSchema.fromBean(CounterpartyBaselineItem.class));
    }

    @Bean
    DynamoDbTable<AccountStatsItem> accountStatsTable(DynamoDbEnhancedClient enhanced) {
        return enhanced.table(Tables.ACCOUNT_STATS,
                software.amazon.awssdk.enhanced.dynamodb.TableSchema.fromBean(AccountStatsItem.class));
    }

    @Bean
    DynamoDbTable<CounterpartyByNameItem> counterpartyByNameTable(DynamoDbEnhancedClient enhanced) {
        return enhanced.table(Tables.COUNTERPARTY_BY_NAME,
                software.amazon.awssdk.enhanced.dynamodb.TableSchema.fromBean(CounterpartyByNameItem.class));
    }

    @Bean
    DynamoDbTable<DecisionItem> decisionTable(DynamoDbEnhancedClient enhanced) {
        return enhanced.table(Tables.DECISIONS,
                software.amazon.awssdk.enhanced.dynamodb.TableSchema.fromBean(DecisionItem.class));
    }

    @Bean
    DynamoDbTable<ObservationItem> observationTable(DynamoDbEnhancedClient enhanced) {
        return enhanced.table(Tables.OBSERVATIONS,
                software.amazon.awssdk.enhanced.dynamodb.TableSchema.fromBean(ObservationItem.class));
    }

    @Bean
    DynamoDbTable<AccountPostureItem> accountPostureTable(DynamoDbEnhancedClient enhanced) {
        return enhanced.table(Tables.ACCOUNT_POSTURE,
                software.amazon.awssdk.enhanced.dynamodb.TableSchema.fromBean(AccountPostureItem.class));
    }

    @Bean
    DynamoDbTable<CounterpartyReputationItem> counterpartyReputationTable(DynamoDbEnhancedClient enhanced) {
        return enhanced.table(Tables.COUNTERPARTY_REPUTATION,
                software.amazon.awssdk.enhanced.dynamodb.TableSchema.fromBean(CounterpartyReputationItem.class));
    }

    @Bean
    DynamoDbTable<AccountItem> accountTable(DynamoDbEnhancedClient enhanced) {
        return enhanced.table(Tables.ACCOUNTS,
                software.amazon.awssdk.enhanced.dynamodb.TableSchema.fromBean(AccountItem.class));
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
}
