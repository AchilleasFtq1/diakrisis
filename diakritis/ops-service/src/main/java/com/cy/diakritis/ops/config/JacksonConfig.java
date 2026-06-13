package com.cy.diakritis.ops.config;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Global Jackson policy for the ops-service HTTP layer (Spring Boot 4 / Jackson 3): {@code snake_case}
 * property names and ISO-8601 timestamps rather than numeric epoch arrays. The same autoconfigured
 * {@link JsonMapper} is injected into {@code OpsService} to parse the verbatim decision JSON stored by
 * decision-service.
 *
 * <p>Jackson 3 bundles {@code java.time} support in the core databind module, so no separate
 * JavaTimeModule registration is required for {@link java.time.Instant} to serialize correctly.
 */
@Configuration
public class JacksonConfig {

    @Bean
    JsonMapperBuilderCustomizer diakrisisJacksonCustomizer() {
        return (JsonMapper.Builder builder) -> builder
                .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS);
    }
}
