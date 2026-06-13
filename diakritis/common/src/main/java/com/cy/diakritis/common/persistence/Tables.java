package com.cy.diakritis.common.persistence;

/**
 * Physical DynamoDB table name constants. Centralized so every module references the same
 * literals and no name is hard-coded at a call site.
 */
public final class Tables {

    public static final String COUNTERPARTY_BASELINE = "CounterpartyBaseline";
    public static final String ACCOUNT_STATS = "AccountStats";
    public static final String COUNTERPARTY_BY_NAME = "CounterpartyByName";
    public static final String DECISIONS = "Decisions";
    public static final String OBSERVATIONS = "Observations";
    public static final String ACCOUNT_POSTURE = "AccountPosture";
    public static final String COUNTERPARTY_REPUTATION = "CounterpartyReputation";
    public static final String ACCOUNTS = "Accounts";
    public static final String PAYEES = "Payees";
    public static final String CASES = "Cases";
    public static final String OUTCOMES = "Outcomes";
    public static final String USERS = "Users";
    public static final String REFRESH_TOKENS = "RefreshTokens";

    private Tables() {
    }
}
