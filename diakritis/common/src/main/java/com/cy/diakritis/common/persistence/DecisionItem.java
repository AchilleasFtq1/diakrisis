package com.cy.diakritis.common.persistence;

import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSortKey;

/**
 * Persisted decision record for idempotent replay.
 * <p>pk = {@code EVENT#<id>}, sk = {@code DECISION}. {@code responseJson} is the verbatim
 * serialized {@code Decision} body so replays return byte-identical output.
 */
@DynamoDbBean
public class DecisionItem {

    private String pk;
    private String sk;
    private String eventId;
    private String accountId;
    private String initiatorSub;
    private long createdEpochMs;
    private String responseJson;
    private String lifecycleState;
    private long holdExpiresEpochMs;
    private long amountCents;
    // Per-event request context, persisted at decision time so the ops console can show what actually
    // happened on this transaction (device, network, geo, channel, beneficiary) — not just the account's
    // running observations. Populated from the ActionEvent's SessionContext + payload.
    private String eventType;
    private long eventTsEpochMs;
    private String channel;
    private String ip;
    private String network;
    private String geoCountry;
    private String deviceId;
    private String devicePlatform;
    private String sessionId;
    private String rail;
    private String counterpartyName;
    private String counterpartyRef;
    private String counterpartyAddressing;

    @DynamoDbPartitionKey
    public String getPk() {
        return pk;
    }

    public void setPk(String pk) {
        this.pk = pk;
    }

    @DynamoDbSortKey
    public String getSk() {
        return sk;
    }

    public void setSk(String sk) {
        this.sk = sk;
    }

    public String getEventId() {
        return eventId;
    }

    public void setEventId(String eventId) {
        this.eventId = eventId;
    }

    public String getAccountId() {
        return accountId;
    }

    public void setAccountId(String accountId) {
        this.accountId = accountId;
    }

    public String getInitiatorSub() {
        return initiatorSub;
    }

    public void setInitiatorSub(String initiatorSub) {
        this.initiatorSub = initiatorSub;
    }

    public long getCreatedEpochMs() {
        return createdEpochMs;
    }

    public void setCreatedEpochMs(long createdEpochMs) {
        this.createdEpochMs = createdEpochMs;
    }

    public String getResponseJson() {
        return responseJson;
    }

    public void setResponseJson(String responseJson) {
        this.responseJson = responseJson;
    }

    public String getLifecycleState() {
        return lifecycleState;
    }

    public void setLifecycleState(String lifecycleState) {
        this.lifecycleState = lifecycleState;
    }

    public long getHoldExpiresEpochMs() {
        return holdExpiresEpochMs;
    }

    public void setHoldExpiresEpochMs(long holdExpiresEpochMs) {
        this.holdExpiresEpochMs = holdExpiresEpochMs;
    }

    /** Action amount in euro-cents (0 for non-monetary actions), used by the §9.5 money-saved counter. */
    public long getAmountCents() {
        return amountCents;
    }

    public void setAmountCents(long amountCents) {
        this.amountCents = amountCents;
    }

    /** Action type (TRANSFER, MASS_PAYMENT, …) persisted directly rather than recovered from audit text. */
    public String getEventType() {
        return eventType;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    /** The event's own timestamp (SessionContext.ts), distinct from the decision write time. */
    public long getEventTsEpochMs() {
        return eventTsEpochMs;
    }

    public void setEventTsEpochMs(long eventTsEpochMs) {
        this.eventTsEpochMs = eventTsEpochMs;
    }

    public String getChannel() {
        return channel;
    }

    public void setChannel(String channel) {
        this.channel = channel;
    }

    public String getIp() {
        return ip;
    }

    public void setIp(String ip) {
        this.ip = ip;
    }

    /** /24 network of {@link #getIp()}, as the engine's G2 (new-network) signal sees it. */
    public String getNetwork() {
        return network;
    }

    public void setNetwork(String network) {
        this.network = network;
    }

    /** Country resolved from the IP at decision time (the basis for the G1 unfamiliar-geo signal). */
    public String getGeoCountry() {
        return geoCountry;
    }

    public void setGeoCountry(String geoCountry) {
        this.geoCountry = geoCountry;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public void setDeviceId(String deviceId) {
        this.deviceId = deviceId;
    }

    public String getDevicePlatform() {
        return devicePlatform;
    }

    public void setDevicePlatform(String devicePlatform) {
        this.devicePlatform = devicePlatform;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    /** Payment rail (SEPA/INSTANT/INTERNAL/P2P) for monetary actions, else null. */
    public String getRail() {
        return rail;
    }

    public void setRail(String rail) {
        this.rail = rail;
    }

    /** Resolved (or display) name of the beneficiary/counterparty, when the action has one. */
    public String getCounterpartyName() {
        return counterpartyName;
    }

    public void setCounterpartyName(String counterpartyName) {
        this.counterpartyName = counterpartyName;
    }

    /** Resolved account reference (or raw value) of the beneficiary — IBAN/account/alias. */
    public String getCounterpartyRef() {
        return counterpartyRef;
    }

    public void setCounterpartyRef(String counterpartyRef) {
        this.counterpartyRef = counterpartyRef;
    }

    /** How the beneficiary was addressed (IBAN/ACCOUNT/MSISDN/EMAIL). */
    public String getCounterpartyAddressing() {
        return counterpartyAddressing;
    }

    public void setCounterpartyAddressing(String counterpartyAddressing) {
        this.counterpartyAddressing = counterpartyAddressing;
    }
}
