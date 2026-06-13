package com.cy.diakritis.decision.config;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Global Jackson policy for the decision service (Spring Boot 4 / Jackson 3): {@code snake_case}
 * property names, ISO-8601 timestamps, and strict rejection of unknown properties so malformed
 * inputs are caught and surface as 4xx (never silently ignored).
 *
 * <p>Jackson 3 bundles {@code java.time} support in core databind, so {@link java.time.Instant}
 * serializes as ISO-8601 with {@link DateTimeFeature#WRITE_DATES_AS_TIMESTAMPS} disabled — no
 * separate JavaTimeModule registration is required.
 */
@Configuration
public class JacksonConfig {

    @Bean
    JsonMapperBuilderCustomizer diakrisisJacksonCustomizer() {
        return (JsonMapper.Builder builder) -> builder
                .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }
}
