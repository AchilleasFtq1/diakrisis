package com.cy.demobank.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;

/**
 * Wires the outbound HTTP client demo-bank uses to talk to the Diakrisis services. Those APIs use
 * {@code snake_case} JSON (account_id, refresh_token, event_type, amount_eur, available_balance_eur,
 * …) and ISO-8601 timestamps, so this configures a dedicated {@link JsonMapper} with the matching
 * policy and a {@link RestClient} that uses it.
 *
 * <p>The mapper is scoped to the outbound client (a named bean) rather than the global MVC mapper so
 * demo-bank's own Thymeleaf/form layer keeps Spring's defaults. It tolerates unknown response
 * properties (the {@code Decision} payload is rich; we read only the fields we render) but emits only
 * the exact snake_case fields the decision-service expects, which rejects unknown inputs.
 */
@Configuration
@EnableConfigurationProperties(DiakrisisProperties.class)
public class DiakrisisClientConfig {

    /** Snake_case + ISO-8601 mapper for the Diakrisis wire format. Outbound-client scoped. */
    @Bean
    JsonMapper diakrisisJsonMapper() {
        return JsonMapper.builder()
                .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
                // Be lenient on the inbound Decision: only a subset of its fields is rendered.
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();
    }

    /**
     * The shared {@link RestClient} for both IAM and decision calls. No base-url is bound here: the
     * caller passes an absolute URL resolved from {@link DiakrisisProperties} so a single
     * gateway override can repoint every call. The Jackson message converter is swapped for the
     * snake_case one so request/response bodies use the Diakrisis wire format.
     */
    @Bean
    RestClient diakrisisRestClient(JsonMapper diakrisisJsonMapper) {
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory();
        requestFactory.setReadTimeout(Duration.ofSeconds(20));

        JacksonJsonHttpMessageConverter snakeCaseConverter =
                new JacksonJsonHttpMessageConverter(diakrisisJsonMapper);

        return RestClient.builder()
                .requestFactory(requestFactory)
                .defaultHeaders(headers -> headers.setAccept(java.util.List.of(MediaType.APPLICATION_JSON)))
                .messageConverters(converters -> {
                    converters.removeIf(converter -> converter instanceof JacksonJsonHttpMessageConverter);
                    converters.add(snakeCaseConverter);
                })
                .build();
    }
}
