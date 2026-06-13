package com.cy.diakritis.iam.config;

import com.cy.diakritis.common.persistence.DynamoConfigSupport;
import com.cy.diakritis.common.persistence.DynamoProperties;
import com.cy.diakritis.common.persistence.RefreshTokenItem;
import com.cy.diakritis.common.persistence.TableBootstrap;
import com.cy.diakritis.common.persistence.TableSchema;
import com.cy.diakritis.common.persistence.Tables;
import com.cy.diakritis.common.persistence.UserItem;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

import java.util.List;

/**
 * Wires the AWS SDK v2 DynamoDB clients and the typed tables iam-service owns ({@code Users},
 * {@code RefreshTokens}). When {@code diakrisis.dynamo.auto-create} is enabled the required tables
 * are provisioned at startup so a fresh local DynamoDB boots cleanly.
 *
 * <p>Both identity tables are partition-key-only ({@code USER#<username>} / {@code RT#<tokenId>}).
 */
@Configuration
public class DynamoConfig {

    @Bean
    DynamoDbClient dynamoDbClient(DynamoProperties properties) {
        DynamoDbClient client = DynamoConfigSupport.client(properties);
        if (properties.isAutoCreate()) {
            TableBootstrap.createIfMissing(client, List.of(
                    TableSchema.of(Tables.USERS, "pk", null),
                    TableSchema.of(Tables.REFRESH_TOKENS, "pk", null)));
        }
        return client;
    }

    @Bean
    DynamoDbEnhancedClient dynamoDbEnhancedClient(DynamoDbClient client) {
        return DynamoConfigSupport.enhanced(client);
    }

    @Bean
    DynamoDbTable<UserItem> userTable(DynamoDbEnhancedClient enhanced) {
        return enhanced.table(Tables.USERS,
                software.amazon.awssdk.enhanced.dynamodb.TableSchema.fromBean(UserItem.class));
    }

    @Bean
    DynamoDbTable<RefreshTokenItem> refreshTokenTable(DynamoDbEnhancedClient enhanced) {
        return enhanced.table(Tables.REFRESH_TOKENS,
                software.amazon.awssdk.enhanced.dynamodb.TableSchema.fromBean(RefreshTokenItem.class));
    }
}
