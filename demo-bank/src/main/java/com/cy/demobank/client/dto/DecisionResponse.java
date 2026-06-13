package com.cy.demobank.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * The subset of the Diakrisis {@code Decision} response demo-bank renders. The full payload is rich;
 * the outbound mapper tolerates the unread fields. The rendered verdict is taken from
 * {@code combined.decision} (falling back to {@code engine_verdict.decision}) and the explanation
 * from {@code explanation.customer} / {@code explanation.audit}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DecisionResponse(
        String eventId,
        EngineVerdict engineVerdict,
        Combined combined,
        Explanation explanation,
        String reasonCode,
        long latencyMs
) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record EngineVerdict(
            int score,
            String decision,
            boolean scaExempt,
            List<String> typologies
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Combined(
            String decision,
            String basis,
            String reasonCode,
            String reviewFlag
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Explanation(
            String customer,
            String audit
    ) {
    }

    /** The verdict to act on: combined if present, else the engine verdict. */
    public String effectiveDecision() {
        if (combined != null && combined.decision() != null) {
            return combined.decision();
        }
        if (engineVerdict != null && engineVerdict.decision() != null) {
            return engineVerdict.decision();
        }
        return null;
    }

    /** The typologies named by the engine (the patterns), or an empty list. */
    public List<String> typologies() {
        return engineVerdict == null || engineVerdict.typologies() == null
                ? List.of()
                : engineVerdict.typologies();
    }

    /** The customer-facing explanation, or null on a clean ALLOW. */
    public String customerExplanation() {
        return explanation == null ? null : explanation.customer();
    }

    /** The audit-facing explanation. */
    public String auditExplanation() {
        return explanation == null ? null : explanation.audit();
    }
}
