package com.cy.diakritis.common.dto;

import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ValueDeserializer;

import java.util.Set;

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
 *
 * <p>Because we hand-read the envelope from a {@link JsonNode} tree (rather than letting Jackson bind
 * it field-by-field), the mapper's {@link DeserializationFeature#FAIL_ON_UNKNOWN_PROPERTIES} would not
 * apply to envelope-level keys — an unknown/misspelled top-level field (e.g. {@code event_typ}) would
 * be silently dropped, defeating the service's strict-input contract. We therefore re-assert that
 * strictness here: any envelope property outside {@link #KNOWN_PROPERTIES} is routed through
 * {@link DeserializationContext#handleUnknownProperty}, which raises an {@code UnrecognizedPropertyException}
 * (→ HTTP 400) when the feature is enabled, exactly as nested object binding does.
 */
public final class ActionEventDeserializer extends ValueDeserializer<ActionEvent> {

    /** The only top-level envelope keys this deserializer recognises; anything else is rejected. */
    private static final Set<String> KNOWN_PROPERTIES =
            Set.of("event_id", "account_id", "event_type", "payload", "context");

    @Override
    public ActionEvent deserialize(JsonParser parser, DeserializationContext ctxt) {
        JsonNode root = ctxt.readTree(parser);

        rejectUnknownEnvelopeFields(parser, ctxt, root);

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

    /**
     * Mirror the default bean path's {@link DeserializationFeature#FAIL_ON_UNKNOWN_PROPERTIES} behaviour
     * for the hand-read envelope: when the feature is enabled, any top-level property outside
     * {@link #KNOWN_PROPERTIES} is reported via {@link DeserializationContext#handleUnknownProperty},
     * which (absent a configured problem handler) throws {@code UnrecognizedPropertyException} so the
     * web layer surfaces a 400 instead of silently dropping the field. When the feature is disabled the
     * call is skipped so the lenient configuration still drops unknown fields as before.
     */
    private void rejectUnknownEnvelopeFields(JsonParser parser, DeserializationContext ctxt, JsonNode root) {
        if (!ctxt.isEnabled(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)) {
            return;
        }
        for (String propertyName : root.propertyNames()) {
            if (!KNOWN_PROPERTIES.contains(propertyName)) {
                ctxt.handleUnknownProperty(parser, this, ActionEvent.class, propertyName);
            }
        }
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
