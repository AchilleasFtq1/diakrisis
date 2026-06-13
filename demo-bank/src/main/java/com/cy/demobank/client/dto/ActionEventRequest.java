package com.cy.demobank.client.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The Diakrisis {@code ActionEvent} envelope, serialized to snake_case by the outbound mapper:
 * {@code {event_id, account_id, event_type, payload, context}}. The decision-service resolves the
 * concrete payload subtype from {@code event_type}, so no discriminator is carried in the payload.
 *
 * <p>NOTE: the decision-service enables FAIL_ON_UNKNOWN_PROPERTIES, so this envelope and every nested
 * payload MUST contain only fields the Diakrisis DTOs declare. {@link JsonInclude.Include#NON_NULL}
 * keeps optional fields (e.g. a null {@code beneficiary_created_at}) off the wire entirely.
 *
 * @param payload one of the {@code *Payload} records in this package, selected by {@code eventType}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ActionEventRequest(
        String eventId,
        String accountId,
        String eventType,
        Object payload,
        SessionContextDto context
) {
}
