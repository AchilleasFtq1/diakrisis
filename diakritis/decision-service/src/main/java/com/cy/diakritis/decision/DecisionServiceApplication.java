package com.cy.diakritis.decision;

import com.cy.diakritis.common.persistence.DynamoProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Decision service ({@code :8081}). Scores inbound {@link com.cy.diakritis.common.dto.ActionEvent}s
 * through the rules+model engine, combines them with the AI co-judge, persists an idempotent
 * decision record, commits account posture, and drives the held/approval lifecycle.
 */
@SpringBootApplication
@EnableConfigurationProperties({DynamoProperties.class, EngineProperties.class})
public class DecisionServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(DecisionServiceApplication.class, args);
    }
}
