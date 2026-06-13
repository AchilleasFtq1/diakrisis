package com.cy.diakritis.iam;

import com.cy.diakritis.common.persistence.DynamoProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Identity + admin service ({@code :8083}). Owns the {@code Users} and {@code RefreshTokens} tables,
 * mints JWTs at {@code /auth/login}, and exposes the ADMIN-only {@code /admin/users/*} console. Split
 * out of the former bank-app: the product's identity provider, independent of the demo banking layer.
 */
@SpringBootApplication
@EnableConfigurationProperties(DynamoProperties.class)
public class IamServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(IamServiceApplication.class, args);
    }
}
