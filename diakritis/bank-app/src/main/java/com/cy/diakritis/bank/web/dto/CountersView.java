package com.cy.diakritis.bank.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

/**
 * Aggregate decision counters for the ops dashboard, broken down by lifecycle state.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CountersView(
        long total,
        Map<String, Long> byLifecycleState
) {
}
