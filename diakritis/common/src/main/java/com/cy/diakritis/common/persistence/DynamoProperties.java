package com.cy.diakritis.common.persistence;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * DynamoDB connection settings. Bound from the {@code diakrisis.dynamo} prefix.
 *
 * <p>Defaults target a local DynamoDB instance (docker-compose service {@code dynamodb}).
 */
@ConfigurationProperties(prefix = "diakrisis.dynamo")
public class DynamoProperties {

    private String endpoint = "http://localhost:8000";
    private String region = "us-east-1";
    private boolean autoCreate = true;

    public String getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public boolean isAutoCreate() {
        return autoCreate;
    }

    public void setAutoCreate(boolean autoCreate) {
        this.autoCreate = autoCreate;
    }
}
