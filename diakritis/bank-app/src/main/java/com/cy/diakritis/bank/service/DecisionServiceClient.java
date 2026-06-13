package com.cy.diakritis.bank.service;

import com.cy.diakritis.common.dto.ActionEvent;
import com.cy.diakritis.common.dto.Decision;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/**
 * Thin client over the decision-service {@code POST /decision} endpoint. The inbound bearer is
 * forwarded automatically by the configured {@link RestClient} interceptor, so this class only
 * concerns itself with the request/response bodies.
 */
@Service
public class DecisionServiceClient {

    private static final String DECISION_PATH = "/decision";

    private final RestClient decisionServiceRestClient;

    public DecisionServiceClient(RestClient decisionServiceRestClient) {
        this.decisionServiceRestClient = decisionServiceRestClient;
    }

    public Decision decide(ActionEvent event) {
        return decisionServiceRestClient.post()
                .uri(DECISION_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .body(event)
                .retrieve()
                .body(Decision.class);
    }
}
