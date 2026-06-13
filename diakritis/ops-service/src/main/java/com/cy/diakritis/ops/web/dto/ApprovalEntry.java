package com.cy.diakritis.ops.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

/**
 * An approval-pending case awaiting an approver's action.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApprovalEntry(
        String eventId,
        String state,
        String initiatorUserId,
        Instant holdExpiresAt,
        List<String> batchHeldItemIds,
        Instant createdAt
) {
}
