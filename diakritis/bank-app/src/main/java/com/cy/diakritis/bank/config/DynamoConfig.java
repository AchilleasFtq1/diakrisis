package com.cy.diakritis.bank.config;

import com.cy.diakritis.common.persistence.AccountItem;
import com.cy.diakritis.common.persistence.CaseItem;
import com.cy.diakritis.common.persistence.DecisionItem;
import com.cy.diakritis.common.persistence.DynamoConfigSupport;
import com.cy.diakritis.common.persistence.DynamoProperties;
import com.cy.diakritis.common.persistence.PayeeItem;
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
 * Wires the AWS SDK v2 DynamoDB clients and the typed tables bank-app owns ({@code Accounts},
 * {@code Payees}). When {@code diakrisis.dynamo.auto-create} is enabled the required tables are
 * provisioned at startup so a fresh local DynamoDB boots cleanly.
 */
@Configuration
public class DynamoConfig {

    @Bean
    DynamoDbClient dynamoDbClient(DynamoProperties properties) {
        DynamoDbClient client = DynamoConfigSupport.client(properties);
        if (properties.isAutoCreate()) {
            TableBootstrap.createIfMissing(client, List.of(
                    TableSchema.of(Tables.ACCOUNTS, "pk", "sk"),
                    TableSchema.of(Tables.PAYEES, "pk", "sk"),
                    TableSchema.of(Tables.DECISIONS, "pk", "sk"),
                    TableSchema.of(Tables.CASES, "pk", "sk")));
        }
        return client;
    }

    @Bean
    DynamoDbEnhancedClient dynamoDbEnhancedClient(DynamoDbClient client) {
        return DynamoConfigSupport.enhanced(client);
    }

    @Bean
    DynamoDbTable<AccountItem> accountTable(DynamoDbEnhancedClient enhanced) {
        return enhanced.table(Tables.ACCOUNTS,
                software.amazon.awssdk.enhanced.dynamodb.TableSchema.fromBean(AccountItem.class));
    }

    @Bean
    DynamoDbTable<PayeeItem> payeeTable(DynamoDbEnhancedClient enhanced) {
        return enhanced.table(Tables.PAYEES,
                software.amazon.awssdk.enhanced.dynamodb.TableSchema.fromBean(PayeeItem.class));
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
}
