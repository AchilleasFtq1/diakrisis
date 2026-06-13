# Diakrisis

**A real-time risk-decision API a bank calls *before* executing any customer action.**

Diakrisis scores a customer action from explainable signals — counterparty intelligence,
amount behaviour, velocity, geo/device context, and **kill-chain posture** (what this account
did in the last 72 h) — and returns one of five graduated, pattern-specific outcomes. It does
not try to out-detect the incumbents; it owns the layer they leave to the bank: the
**customer-facing decision itself**.

> Authorized-push-payment (APP) scams defeat binary fraud checks structurally: the genuine
> customer, on genuine credentials, authorizes every step — including the *preparatory* ones
> (break the deposit, raise the limit, add the payee, then send). Each step looks unremarkable.
> **The sequence is the scam.** Diakrisis scores the sequence, names the pattern to the victim
> at decision time, and adds friction *proportional to risk* — removing it when risk is low.

## Repository layout

```
diakrisis/                  ← this repo
├── diakritis/              the system — a Maven reactor (build + run here)
│   ├── common/             shared DTOs, JWT, DynamoDB beans
│   ├── engine/             decision pipeline: signals, typologies, bands, M1 loader
│   ├── etl/                Berka → DynamoDB feature tables + demo seed (CLI)
│   ├── decision-service/   Spring Boot app :8081 (the decision API)
│   ├── bank-app/           Spring Boot app :8080 (mini-bank + ops + JWT)
│   └── README.md           detailed module + engine docs
└── diakrisis-models/       pre-trained M1 model artifacts (loaded, never retrained)
```

## Five graduated outcomes

| Outcome | Friction |
|---|---|
| `ALLOW` | none (SCA-exempt where PSD2 RTS Art. 18 TRA permits) — the product working silently |
| `CONFIRM` | one specific sentence + one tap (purpose prompt on liquidity actions) |
| `REQUIRE_APPROVAL` | a second authorized person must approve (four-eyes; initiator can never self-approve) |
| `HOLD` | 30-min cooling-off + scam-pattern-naming warning + one-tap cancel (never auto-executes) |
| `BLOCK` | stopped; manual review (rare by design) |

## Architecture

Two deployables behind one shared JWT identity, over one DynamoDB:

- **`decision-service`** (:8081) — the whole decision engine API (signals → typologies →
  score/band → policy → ISO 20022 reason code). Authoritative, deterministic, `< 50 ms`.
- **`bank-app`** (:8080) — mini-bank (accounts, payees, transfer/P2P/batch, deposit break) +
  ops dashboard + JWT issuer. Builds an `ActionEvent` and calls `decision-service` before
  executing, forwarding the caller's Bearer.
- **DynamoDB Local** (Docker) — read-only feature tables (ETL-written) + read-write runtime
  state (decisions, observations, 72 h posture, counterparty reputation; `TTL = 72 h` ↔ decay).
- **Auth** — HS256 JWT, roles `CUSTOMER` / `APPROVER` / `OPS`, shared `DIAKRISIS_JWT_SECRET`.

**Stack:** Java 26 · Spring Boot 4.1.0 · Maven · Jackson (records) · Smile 3.1.1 (pure-JVM GBT) ·
AWS SDK v2 DynamoDB Enhanced Client · jjwt (HS256) · commons-csv · JUnit 5. Virtual threads on.

## Build & run

```bash
cd diakritis
docker compose up -d dynamodb                       # DynamoDB Local :8000
mvn clean package                                   # build the reactor (Java 26)
java -jar etl/target/etl-*.jar \                    # seed from real Berka history
     --berka-dir data/raw/berka --ddb-endpoint http://localhost:8000 --demo
export DIAKRISIS_JWT_SECRET='change-me-to-a-32-byte-minimum-secret!!'
java -jar decision-service/target/decision-service-*.jar    # :8081
java -jar bank-app/target/bank-app-*.jar                    # :8080
```

The engine loads model artifacts from `diakrisis.models-dir` (default `../diakrisis-models`).
Full module/engine documentation: [`diakritis/README.md`](diakritis/README.md).

## Golden path (T1–T6)

`POST /decision` is pinned to exact verdicts (`diakritis/qa/golden-path-scenarios.md`):
T1/T2 established payees → `ALLOW` + SCA-exempt · T3 anomalous amount → `CONFIRM` ·
T4 invoice redirection → `HOLD` · T5a deposit break → `CONFIRM` + purpose · T5b kill-chain
drain 20 min later → `HOLD`. T5a → T5b is the centre: the drain is held **because the engine
remembered** the deposit break.

## Data provenance (stated honestly)

No public corpus of labelled APP-scam transfers exists. So legitimate-behaviour baselines are
**real** (Berka PKDD'99 — 1,056,320 rows, 208,283 outgoing transfers), the fraud-trained ML
signal is **real** (IEEE-CIS card fraud), and exactly **three constructs** are disclosed
(`source="CONSTRUCTED"`) only where Berka has no such data: the confirmation-of-payee *name*
(T4), the *term deposit* (T5), and the *escalation* sequence (T6). Same epistemic position as
every vendor — the difference is saying it first.

## Status

Backend decision path (engine, both services, ETL, T1–T6 golden-path tests on real Berka
history). The React mini-bank + ops dashboard (Vite + Tailwind + Recharts) and the optional
Gemma co-judge / Qdrant M2 vector path are roadmap — behind the same interfaces, absent rather
than stubbed.
