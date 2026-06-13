package com.cy.diakritis.ops.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI metadata for the ops service plus a reusable HTTP-bearer (JWT) security scheme so the
 * Swagger UI "Authorize" dialog accepts a raw token and forwards it as {@code Authorization: Bearer
 * <jwt>} on every try-it-out call. Every {@code /ops/*} endpoint requires an OPS or APPROVER JWT
 * (minted by iam-service POST /auth/login).
 */
@Configuration
public class OpenApiConfig {

    private static final String BEARER_SCHEME_NAME = "bearer-jwt";

    @Bean
    OpenAPI opsServiceOpenApi() {
        SecurityScheme bearerScheme = new SecurityScheme()
                .name(BEARER_SCHEME_NAME)
                .type(SecurityScheme.Type.HTTP)
                .scheme("bearer")
                .bearerFormat("JWT")
                .description("Paste a JWT minted by iam-service POST /auth/login (without the 'Bearer ' prefix).");

        return new OpenAPI()
                .info(new Info()
                        .title("Diakrisis Ops Service API")
                        .version("0.1.0")
                        .description("""
                                Analyst dashboard service (:8082). Read-only OPS/APPROVER projections over the \
                                decision and case stores: /ops/feed (recent decisions), /ops/counters (aggregate \
                                metrics + feedback-loop counters), /ops/approvals (four-eyes pending queue). All \
                                endpoints require a Bearer JWT with the OPS or APPROVER role."""))
                .components(new Components().addSecuritySchemes(BEARER_SCHEME_NAME, bearerScheme))
                .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME_NAME));
    }
}
