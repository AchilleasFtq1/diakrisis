package com.cy.diakritis.decision.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI metadata for the decision service plus a reusable HTTP-bearer (JWT) security scheme so the
 * Swagger UI "Authorize" dialog accepts a raw token and forwards it as {@code Authorization: Bearer
 * <jwt>} on every try-it-out call. The scheme is also applied as a global requirement so every
 * operation is annotated as protected (the {@code /decision} and {@code /actions/**} paths read the
 * resolved principal for four-eyes and audit).
 */
@Configuration
public class OpenApiConfig {

    private static final String BEARER_SCHEME_NAME = "bearer-jwt";

    @Bean
    OpenAPI decisionServiceOpenApi() {
        SecurityScheme bearerScheme = new SecurityScheme()
                .name(BEARER_SCHEME_NAME)
                .type(SecurityScheme.Type.HTTP)
                .scheme("bearer")
                .bearerFormat("JWT")
                .description("Paste a JWT minted by bank-app POST /auth/login (without the 'Bearer ' prefix).");

        return new OpenAPI()
                .info(new Info()
                        .title("Diakrisis Decision Service API")
                        .version("0.1.0")
                        .description("""
                                Scoring + lifecycle decision API (:8081). POST /decision scores an ActionEvent into a \
                                Decision (idempotent on event_id); the /actions/{id}/* endpoints drive the \
                                confirm / cancel / release / approve / reject lifecycle (four-eyes + pre-expiry \
                                guards); GET /decisions/{id}/why returns the audit + customer explanation. \
                                All endpoints except the docs surface require a Bearer JWT."""))
                .components(new Components().addSecuritySchemes(BEARER_SCHEME_NAME, bearerScheme))
                .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME_NAME));
    }
}
