package com.cy.diakritis.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Diakrisis API Gateway — the single front door (:8080) of the fraud product.
 *
 * <p>Built on Spring Cloud Gateway Server MVC (servlet) 5.0.x (Spring Cloud 2025.1.1 / Spring Boot
 * 4.0.x). It forwards HTTP to the three backend services (decision :8081, ops :8082, iam :8083),
 * enforces an edge JWT check on protected routes (self-contained HS256, same secret as the backends),
 * applies global CORS, and serves one aggregated Swagger UI listing all three backend API docs.
 *
 * <p>Routes are declared in {@code application.yml}; the cross-cutting concerns (edge auth, CORS) live
 * in {@code com.cy.diakritis.gateway.security} and {@code com.cy.diakritis.gateway.config}.
 */
@SpringBootApplication
public class ApiGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(ApiGatewayApplication.class, args);
    }
}
