package com.cy.diakritis.engine;

import com.cy.diakritis.common.dto.ActionEvent;
import com.cy.diakritis.common.dto.ActionPayload;
import com.cy.diakritis.common.dto.Addressing;
import com.cy.diakritis.common.dto.BatchItem;
import com.cy.diakritis.common.dto.Channel;
import com.cy.diakritis.common.dto.Counterparty;
import com.cy.diakritis.common.dto.DepositBreakPayload;
import com.cy.diakritis.common.dto.DeviceInfo;
import com.cy.diakritis.common.dto.EventType;
import com.cy.diakritis.common.dto.MassPaymentPayload;
import com.cy.diakritis.common.dto.Platform;
import com.cy.diakritis.common.dto.Rail;
import com.cy.diakritis.common.dto.SessionContext;
import com.cy.diakritis.common.dto.TransferPayload;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Builders for the action events used across engine tests (T1-T6 seed scenarios). */
public final class Events {

    private Events() {
    }

    public static SessionContext session(String sessionId, Instant now) {
        return new SessionContext(now, sessionId, Channel.MOBILE_APP, "203.0.113.7",
                new DeviceInfo("device-1", Platform.IOS));
    }

    public static Counterparty payee(String iban, String resolvedName, Instant createdAt) {
        return new Counterparty(Addressing.IBAN, iban, iban, resolvedName,
                resolvedName, createdAt);
    }

    public static ActionEvent transfer(String eventId, String accountId, Counterparty cp,
                                double amountEur, double availableEur, Rail rail, Instant now) {
        ActionPayload payload = new TransferPayload(cp, BigDecimal.valueOf(amountEur),
                BigDecimal.valueOf(availableEur), rail);
        return new ActionEvent(eventId, accountId, EventType.TRANSFER, payload, session("sess-" + eventId, now));
    }

    public static ActionEvent transferInSession(String eventId, String accountId, Counterparty cp,
                                         double amountEur, double availableEur, Rail rail,
                                         String sessionId, Instant now) {
        ActionPayload payload = new TransferPayload(cp, BigDecimal.valueOf(amountEur),
                BigDecimal.valueOf(availableEur), rail);
        return new ActionEvent(eventId, accountId, EventType.TRANSFER, payload, session(sessionId, now));
    }

    public static ActionEvent depositBreak(String eventId, String accountId, String depositId,
                                    double principalEur, double penaltyEur, Instant maturity, Instant now) {
        ActionPayload payload = new DepositBreakPayload(depositId, BigDecimal.valueOf(principalEur),
                maturity, BigDecimal.valueOf(penaltyEur));
        return new ActionEvent(eventId, accountId, EventType.TERM_DEPOSIT_BREAK, payload,
                session("sess-" + eventId, now));
    }

    /** A session context with an explicit IP, device id and platform (for geo/device signals). */
    public static SessionContext session(String sessionId, Instant now, String ip,
                                         String deviceId, Platform platform) {
        return new SessionContext(now, sessionId, Channel.MOBILE_APP, ip,
                new DeviceInfo(deviceId, platform));
    }

    /** A P2P transfer to an alias-addressed counterparty (MSISDN), for the P1 re-point signal. */
    public static ActionEvent p2pAlias(String eventId, String accountId, Addressing addressing,
                                       String aliasValue, String resolvedAccountRef, double amountEur,
                                       double availableEur, Instant now) {
        Counterparty cp = new Counterparty(addressing, aliasValue, resolvedAccountRef,
                "Alias Payee", "Alias Payee", null);
        ActionPayload payload = new TransferPayload(cp, BigDecimal.valueOf(amountEur),
                BigDecimal.valueOf(availableEur), Rail.P2P);
        return new ActionEvent(eventId, accountId, EventType.P2P_TRANSFER, payload,
                session("sess-" + eventId, now));
    }

    /** A transfer with a fully-specified session (IP/device/platform), for geo/device signals. */
    public static ActionEvent transferWithSession(String eventId, String accountId, Counterparty cp,
                                                  double amountEur, double availableEur, Rail rail,
                                                  SessionContext session) {
        ActionPayload payload = new TransferPayload(cp, BigDecimal.valueOf(amountEur),
                BigDecimal.valueOf(availableEur), rail);
        EventType type = rail == Rail.P2P ? EventType.P2P_TRANSFER : EventType.TRANSFER;
        return new ActionEvent(eventId, accountId, type, payload, session);
    }

    /** A mass-payment batch of the given lines totalling {@code totalEur} with {@code availableEur}. */
    public static ActionEvent massPayment(String eventId, String accountId, String batchId,
                                          List<BatchItem> items, double totalEur, double availableEur,
                                          Rail rail, Instant now) {
        ActionPayload payload = new MassPaymentPayload(batchId, "PAYROLL", items,
                BigDecimal.valueOf(totalEur), BigDecimal.valueOf(availableEur), rail);
        return new ActionEvent(eventId, accountId, EventType.MASS_PAYMENT, payload,
                session("sess-" + eventId, now));
    }

    /** A single batch line paying {@code cp} {@code amountEur}. */
    public static BatchItem line(String itemId, Counterparty cp, double amountEur) {
        return new BatchItem(itemId, cp, BigDecimal.valueOf(amountEur));
    }
}
