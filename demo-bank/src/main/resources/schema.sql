-- demo-bank SQLite schema. Dropped + recreated on every boot for deterministic demos.
DROP TABLE IF EXISTS payees;
DROP TABLE IF EXISTS deposits;
DROP TABLE IF EXISTS accounts;

CREATE TABLE accounts (
    id                      TEXT PRIMARY KEY,
    display_name            TEXT    NOT NULL,
    available_balance_cents INTEGER NOT NULL,
    owner_user              TEXT    NOT NULL,
    is_business             INTEGER NOT NULL DEFAULT 0,
    vulnerable              INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE payees (
    account_id      TEXT    NOT NULL,
    cp_key          TEXT    NOT NULL,
    iban            TEXT    NOT NULL,
    display_name    TEXT    NOT NULL,
    resolved_name   TEXT,
    created_epoch_ms INTEGER NOT NULL,
    PRIMARY KEY (account_id, cp_key)
);

CREATE TABLE deposits (
    id                TEXT PRIMARY KEY,
    account_id        TEXT    NOT NULL,
    principal_cents   INTEGER NOT NULL,
    maturity_epoch_ms INTEGER NOT NULL,
    penalty_cents     INTEGER NOT NULL,
    broken            INTEGER NOT NULL DEFAULT 0
);
