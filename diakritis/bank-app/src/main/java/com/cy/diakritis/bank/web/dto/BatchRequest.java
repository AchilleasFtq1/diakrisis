package com.cy.diakritis.bank.web.dto;

import com.cy.diakritis.common.dto.Rail;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * A mass-payment batch posted to {@code /batches}. The total is derived server-side from the item
 * amounts; the rail and purpose hint apply to the whole batch.
 */
public record BatchRequest(
        String batchId,
        String purposeHint,
        @NotEmpty @Valid List<BatchItemRequest> items,
        @NotNull Rail rail,
        @NotNull @Valid SessionRequest session
) {
}
