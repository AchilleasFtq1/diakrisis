package com.cy.diakritis.ops.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * An approval-pending case awaiting an approver's action. {@code amountEur} is joined from the
 * matching {@code Decisions} row (null if no decision amount is recorded).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApprovalEntry(
        String eventId,
        String state,
        String initiatorUserId,
        Instant holdExpiresAt,
        List<String> batchHeldItemIds,
        Instant createdAt,
        BigDecimal amountEur
) {
}
