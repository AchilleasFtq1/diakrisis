package com.cy.diakritis.bank.config;

import com.cy.diakritis.bank.DecisionServiceProperties;
import com.cy.diakritis.common.security.BearerTokenHolder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@link RestClient} pointed at decision-service. An interceptor copies the caller's inbound
 * {@code Authorization: Bearer} (staged on {@link BearerTokenHolder} by the JWT filter) onto every
 * outbound request so decision-service authenticates the originating user, not the bank app.
 *
 * <p>The client is configured with a message converter backed by the application's snake_case
 * {@link JsonMapper}: a bare {@code RestClient.builder()} would otherwise serialize the outbound
 * {@link com.cy.diakritis.common.dto.ActionEvent} with a default camelCase mapper, which
 * decision-service (snake_case + strict unknown-property handling) rejects as a malformed body.
 */
@Configuration
public class RestClientConfig {

    @Bean
    ClientHttpRequestInterceptor bearerForwardingInterceptor() {
        return (request, body, execution) -> {
            String token = BearerTokenHolder.get();
            if (token != null && !token.isBlank()) {
                request.getHeaders().set(HttpHeaders.AUTHORIZATION, "Bearer " + token);
            }
            return execution.execute(request, body);
        };
    }

    @Bean
    RestClient decisionServiceRestClient(DecisionServiceProperties properties,
                                         ClientHttpRequestInterceptor bearerForwardingInterceptor,
                                         JsonMapper jsonMapper) {
        JacksonJsonHttpMessageConverter converter = new JacksonJsonHttpMessageConverter(jsonMapper);
        return RestClient.builder()
                .baseUrl(properties.getBaseUrl())
                .requestInterceptor(bearerForwardingInterceptor)
                .messageConverters(converters -> {
                    converters.removeIf(c -> c instanceof JacksonJsonHttpMessageConverter);
                    converters.add(converter);
                })
                .build();
    }
}
