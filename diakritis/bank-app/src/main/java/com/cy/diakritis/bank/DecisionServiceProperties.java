package com.cy.diakritis.bank;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Location of the downstream decision-service. Bound from {@code diakrisis.decision-service}.
 */
@ConfigurationProperties(prefix = "diakrisis.decision-service")
public class DecisionServiceProperties {

    private String baseUrl = "http://localhost:8081";

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }
}
