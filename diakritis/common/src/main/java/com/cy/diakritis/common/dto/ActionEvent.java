package com.cy.diakritis.common.dto;

import tools.jackson.databind.annotation.JsonDeserialize;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * The inbound action to be scored. The concrete {@link ActionPayload} subtype is not
 * carried by an explicit JSON discriminator; instead it is selected from {@code eventType}
 * by {@link ActionEventDeserializer}. That is why the polymorphism is resolved at the
 * envelope level rather than with {@code @JsonTypeInfo} on {@link ActionPayload}.
 */
@JsonDeserialize(using = ActionEventDeserializer.class)
public record ActionEvent(
        @NotBlank String eventId,
        @NotBlank String accountId,
        @NotNull EventType eventType,
        @NotNull @Valid ActionPayload payload,
        @NotNull @Valid SessionContext context
) {
}
