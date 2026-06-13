package com.cy.diakritis.bank.config;

import com.cy.diakritis.bank.DecisionServiceProperties;
import com.cy.diakritis.common.security.BearerTokenHolder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.web.client.RestClient;

/**
 * {@link RestClient} pointed at decision-service. An interceptor copies the caller's inbound
 * {@code Authorization: Bearer} (staged on {@link BearerTokenHolder} by the JWT filter) onto every
 * outbound request so decision-service authenticates the originating user, not the bank app.
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
                                         ClientHttpRequestInterceptor bearerForwardingInterceptor) {
        return RestClient.builder()
                .baseUrl(properties.getBaseUrl())
                .requestInterceptor(bearerForwardingInterceptor)
                .build();
    }
}
