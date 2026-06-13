package com.cy.diakritis.bank.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI metadata for the customer-facing bank app plus a reusable HTTP-bearer (JWT) security scheme
 * so the Swagger UI "Authorize" dialog accepts a raw token and forwards it as {@code Authorization:
 * Bearer <jwt>} on every try-it-out call. POST /auth/login is public (mints the token); every other
 * endpoint reads the principal bound to that token.
 */
@Configuration
public class OpenApiConfig {

    private static final String BEARER_SCHEME_NAME = "bearer-jwt";

    @Bean
    OpenAPI bankAppOpenApi() {
        SecurityScheme bearerScheme = new SecurityScheme()
                .name(BEARER_SCHEME_NAME)
                .type(SecurityScheme.Type.HTTP)
                .scheme("bearer")
                .bearerFormat("JWT")
                .description("Paste the JWT returned by POST /auth/login (without the 'Bearer ' prefix).");

        return new OpenAPI()
                .info(new Info()
                        .title("Diakrisis Bank App API")
                        .version("0.1.0")
                        .description("""
                                Customer-facing bank API (:8080). POST /auth/login mints a JWT for the demo users; \
                                the action endpoints (/transfers, /p2p, /payees, /batches, /deposits/{id}/break, \
                                /limits/change) build an ActionEvent from stored account facts and forward it to \
                                decision-service, returning the Decision. /ops/* exposes read-only OPS/APPROVER \
                                projections. All endpoints except /auth/login and the docs surface require a Bearer \
                                JWT."""))
                .components(new Components().addSecuritySchemes(BEARER_SCHEME_NAME, bearerScheme))
                .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME_NAME));
    }
}
