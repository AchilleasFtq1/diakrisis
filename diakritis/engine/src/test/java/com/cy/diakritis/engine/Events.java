package com.cy.diakritis.engine;

import com.cy.diakritis.common.dto.ActionEvent;
import com.cy.diakritis.common.dto.ActionPayload;
import com.cy.diakritis.common.dto.Addressing;
import com.cy.diakritis.common.dto.Channel;
import com.cy.diakritis.common.dto.Counterparty;
import com.cy.diakritis.common.dto.DepositBreakPayload;
import com.cy.diakritis.common.dto.DeviceInfo;
import com.cy.diakritis.common.dto.EventType;
import com.cy.diakritis.common.dto.Platform;
import com.cy.diakritis.common.dto.Rail;
import com.cy.diakritis.common.dto.SessionContext;
import com.cy.diakritis.common.dto.TransferPayload;

import java.math.BigDecimal;
import java.time.Instant;

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
}
