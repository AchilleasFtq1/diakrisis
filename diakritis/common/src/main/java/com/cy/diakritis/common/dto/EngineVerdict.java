package com.cy.diakritis.common.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record EngineVerdict(
        int score,
        Verdict decision,
        boolean scaExempt,
        String scaExemptBasis,
        boolean scaRequired,
        Friction friction,
        List<String> typologies,
        List<Signal> signals
) {
}
