package com.cy.diakritis.iam.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI metadata for the identity service plus a reusable HTTP-bearer (JWT) security scheme so the
 * Swagger UI "Authorize" dialog accepts a raw token and forwards it as {@code Authorization: Bearer
 * <jwt>} on every try-it-out call. The {@code /auth/*} endpoints are public (login mints the token);
 * the {@code /admin/users/*} console reads the principal bound to that token.
 */
@Configuration
public class OpenApiConfig {

    private static final String BEARER_SCHEME_NAME = "bearer-jwt";

    @Bean
    OpenAPI iamServiceOpenApi() {
        SecurityScheme bearerScheme = new SecurityScheme()
                .name(BEARER_SCHEME_NAME)
                .type(SecurityScheme.Type.HTTP)
                .scheme("bearer")
                .bearerFormat("JWT")
                .description("Paste the JWT returned by POST /auth/login (without the 'Bearer ' prefix).");

        return new OpenAPI()
                .info(new Info()
                        .title("Diakrisis IAM Service API")
                        .version("0.1.0")
                        .description("""
                                Identity + admin service (:8083). POST /auth/register and /auth/login mint a \
                                JWT for the product's users; /auth/refresh rotates it and /auth/logout revokes \
                                the refresh token. /admin/users/* is the ADMIN-only management console. All \
                                endpoints except /auth/* and the docs surface require a Bearer JWT."""))
                .components(new Components().addSecuritySchemes(BEARER_SCHEME_NAME, bearerScheme))
                .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME_NAME));
    }
}
