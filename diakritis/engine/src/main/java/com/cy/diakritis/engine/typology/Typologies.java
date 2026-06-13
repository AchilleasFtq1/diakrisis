package com.cy.diakritis.engine.typology;

/**
 * Stable typology identifiers emitted by the {@link TypologyEvaluator}. These are the named scam
 * patterns the engine recognises; they drive band overrides, reason codes and customer messaging.
 */
public final class Typologies {

    private Typologies() {
    }

    public static final String INVOICE_REDIRECTION = "invoice_redirection";
    public static final String ROMANCE_REPEAT_VICTIM = "romance_repeat_victim";
    public static final String LIQUIDATION_KILL_CHAIN = "liquidation_kill_chain";
    public static final String SAFE_ACCOUNT_SCAM = "safe_account_scam";
}
