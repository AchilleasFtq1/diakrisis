package com.cy.diakritis.bank;

import com.cy.diakritis.common.persistence.DynamoProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Customer-facing bank application ({@code :8080}). Mints JWTs at {@code /auth/login}, exposes
 * banking endpoints that build {@link com.cy.diakritis.common.dto.ActionEvent}s from stored
 * account/payee facts, and forwards them to decision-service for scoring.
 */
@SpringBootApplication
@EnableConfigurationProperties({DynamoProperties.class, DecisionServiceProperties.class})
public class BankAppApplication {

    public static void main(String[] args) {
        SpringApplication.run(BankAppApplication.class, args);
    }
}
