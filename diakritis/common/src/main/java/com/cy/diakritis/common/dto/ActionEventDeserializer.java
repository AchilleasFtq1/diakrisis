package com.cy.diakritis.common.dto;

import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ValueDeserializer;

/**
 * Resolves the concrete {@link ActionPayload} subtype from {@code eventType} on the envelope.
 *
 * <p>The wire format does not carry a discriminator inside the payload object, so we read
 * {@code eventType} first and bind the {@code payload} node to the matching concrete record.
 * This keeps the public JSON clean (no synthetic {@code @type} field) while preserving the
 * sealed type hierarchy.
 *
 * <p>This targets Jackson 3 ({@code tools.jackson}) — the mapper the Spring Boot 4 apps run on. The
 * envelope is bound via {@code @JsonDeserialize(using = ...)} on {@link ActionEvent}; a Jackson 2
 * annotation/deserializer would be silently ignored by the Jackson 3 mapper, leaving the abstract
 * {@link ActionPayload} un-instantiable.
 */
public final class ActionEventDeserializer extends ValueDeserializer<ActionEvent> {

    @Override
    public ActionEvent deserialize(JsonParser parser, DeserializationContext ctxt) {
        JsonNode root = ctxt.readTree(parser);

        JsonNode eventTypeNode = root.get("event_type");
        if (eventTypeNode == null || eventTypeNode.isNull()) {
            // Defer to bean-validation by constructing with a null eventType/payload; the
            // @NotNull constraints surface a 4xx rather than a parse-time failure.
            return new ActionEvent(
                    textOrNull(root, "event_id"),
                    textOrNull(root, "account_id"),
                    null,
                    null,
                    readValue(ctxt, root.get("context"), SessionContext.class)
            );
        }

        EventType eventType = ctxt.readTreeAsValue(eventTypeNode, EventType.class);
        Class<? extends ActionPayload> payloadType = payloadClassFor(eventType);

        JsonNode payloadNode = root.get("payload");
        ActionPayload payload = (payloadNode == null || payloadNode.isNull())
                ? null
                : ctxt.readTreeAsValue(payloadNode, payloadType);

        return new ActionEvent(
                textOrNull(root, "event_id"),
                textOrNull(root, "account_id"),
                eventType,
                payload,
                readValue(ctxt, root.get("context"), SessionContext.class)
        );
    }

    private static Class<? extends ActionPayload> payloadClassFor(EventType eventType) {
        return switch (eventType) {
            case TRANSFER, P2P_TRANSFER -> TransferPayload.class;
            case MASS_PAYMENT -> MassPaymentPayload.class;
            case TERM_DEPOSIT_BREAK -> DepositBreakPayload.class;
            case BENEFICIARY_ADD -> BeneficiaryAddPayload.class;
            case LIMIT_CHANGE -> LimitChangePayload.class;
        };
    }

    private static <T> T readValue(DeserializationContext ctxt, JsonNode node, Class<T> type) {
        if (node == null || node.isNull()) {
            return null;
        }
        return ctxt.readTreeAsValue(node, type);
    }

    private static String textOrNull(JsonNode root, String field) {
        JsonNode node = root.get(field);
        return (node == null || node.isNull()) ? null : node.asString();
    }
}
