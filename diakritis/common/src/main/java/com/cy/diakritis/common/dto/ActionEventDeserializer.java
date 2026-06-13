package com.cy.diakritis.common.dto;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;

import java.io.IOException;

/**
 * Resolves the concrete {@link ActionPayload} subtype from {@code eventType} on the envelope.
 *
 * <p>The wire format does not carry a discriminator inside the payload object, so we read
 * {@code eventType} first and bind the {@code payload} node to the matching concrete record.
 * This keeps the public JSON clean (no synthetic {@code @type} field) while preserving the
 * sealed type hierarchy.
 */
public final class ActionEventDeserializer extends StdDeserializer<ActionEvent> {

    public ActionEventDeserializer() {
        super(ActionEvent.class);
    }

    @Override
    public ActionEvent deserialize(JsonParser parser, DeserializationContext ctxt) throws IOException {
        ObjectMapper mapper = (ObjectMapper) parser.getCodec();
        JsonNode root = mapper.readTree(parser);

        JsonNode eventTypeNode = root.get("event_type");
        if (eventTypeNode == null || eventTypeNode.isNull()) {
            // Defer to bean-validation by constructing with a null eventType/payload; the
            // @NotNull constraints surface a 422 rather than a parse-time 500.
            return new ActionEvent(
                    textOrNull(root, "event_id"),
                    textOrNull(root, "account_id"),
                    null,
                    null,
                    readValue(mapper, root.get("context"), SessionContext.class)
            );
        }

        EventType eventType = mapper.convertValue(eventTypeNode, EventType.class);
        Class<? extends ActionPayload> payloadType = payloadClassFor(eventType);

        JsonNode payloadNode = root.get("payload");
        ActionPayload payload = (payloadNode == null || payloadNode.isNull())
                ? null
                : mapper.convertValue(payloadNode, payloadType);

        return new ActionEvent(
                textOrNull(root, "event_id"),
                textOrNull(root, "account_id"),
                eventType,
                payload,
                readValue(mapper, root.get("context"), SessionContext.class)
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

    private static <T> T readValue(ObjectMapper mapper, JsonNode node, Class<T> type) {
        if (node == null || node.isNull()) {
            return null;
        }
        return mapper.convertValue(node, type);
    }

    private static String textOrNull(JsonNode root, String field) {
        JsonNode node = root.get(field);
        return (node == null || node.isNull()) ? null : node.asText();
    }
}
