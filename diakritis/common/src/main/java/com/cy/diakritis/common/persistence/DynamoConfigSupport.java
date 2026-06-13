package com.cy.diakritis.common.persistence;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

import java.net.URI;

/**
 * Factory helpers for the AWS SDK v2 DynamoDB clients pointed at a local endpoint.
 *
 * <p>Local DynamoDB ignores credentials but the SDK still requires a provider; static dummy
 * credentials ({@code local}/{@code local}) satisfy that contract without reaching the real
 * AWS credential chain.
 */
public final class DynamoConfigSupport {

    private DynamoConfigSupport() {
    }

    public static DynamoDbClient client(DynamoProperties properties) {
        if (properties == null) {
            throw new IllegalArgumentException("properties must not be null");
        }
        return DynamoDbClient.builder()
                .endpointOverride(URI.create(properties.getEndpoint()))
                .region(Region.of(properties.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create("local", "local")))
                .build();
    }

    public static DynamoDbEnhancedClient enhanced(DynamoDbClient client) {
        if (client == null) {
            throw new IllegalArgumentException("client must not be null");
        }
        return DynamoDbEnhancedClient.builder()
                .dynamoDbClient(client)
                .build();
    }
}
