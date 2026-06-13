package com.cy.diakritis.ops;

import com.cy.diakritis.common.persistence.DynamoProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Analyst dashboard service ({@code :8082}). Exposes read-only OPS/APPROVER projections
 * ({@code /ops/feed}, {@code /ops/counters}, {@code /ops/approvals}) computed over the
 * {@code Decisions} / {@code Cases} / {@code Outcomes} tables that decision-service writes. Split out
 * of the former bank-app: the product's operations console, decoupled from the demo banking layer.
 */
@SpringBootApplication
@EnableConfigurationProperties(DynamoProperties.class)
public class OpsServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(OpsServiceApplication.class, args);
    }
}
