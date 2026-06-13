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

---

## Five graduated outcomes

| Outcome | Friction | Notes |
|---|---|---|
| `ALLOW` | none (SCA-exempt where PSD2 RTS Art. 18 TRA permits) | the product working silently |
| `CONFIRM` | one specific sentence + one tap (purpose prompt on liquidity actions) | false-positive cost: one tap |
| `REQUIRE_APPROVAL` | a second authorized person must approve (four-eyes) | initiator can never self-approve |
| `HOLD` | 30-min cooling-off + scam-pattern-naming warning + one-tap cancel | never auto-executes; explicit release |
| `BLOCK` | stopped; manual review | rare by design |

Risk up → friction up *with an explanation that names the pattern*. Risk down → friction
removed. The fraud tool that also removes friction.

---

## Architecture

Two deployables behind one shared JWT identity, over one DynamoDB:

```
                         ┌──────────────────────────────┐
  React mini-bank UI ───▶│  bank-app            :8080    │
  React ops dashboard    │  /auth/login (mint JWT)       │
                         │  /accounts /payees /transfers │
                         │  /p2p /batches /deposits/break │
                         │  /ops/feed /counters /approvals│
                         └───────────────┬───────────────┘
                                         │  REST + forwarded Bearer
                                         ▼
                         ┌──────────────────────────────┐
                         │  decision-service    :8081    │
                         │  POST /decision               │
                         │  /actions/{id}/{confirm,cancel,│
                         │     release,approve,reject}    │
                         │  engine: signals → typologies →│
                         │     score/band → policy → ISO  │
                         │  M1 (Smile GBT) · AI co-judge  │
                         └───────────────┬───────────────┘
                                         ▼
                         ┌──────────────────────────────┐
                         │  DynamoDB Local      :8000    │
                         │  features (RO) · decisions ·   │
                         │  observations · posture · rep  │
                         └──────────────────────────────┘
```

- **`decision-service`** (:8081) — the whole decision engine API. Authoritative, deterministic,
  `< 50 ms` decision path. Validates the user JWT.
- **`bank-app`** (:8080) — mini-bank (accounts, payees, transfer/P2P/batch initiation, deposit
  break) + ops dashboard + JWT issuer. Builds an `ActionEvent` from stored facts and calls
  `decision-service` before executing, **forwarding the caller's Bearer**.
- **DynamoDB Local** (Docker) — feature/baseline tables are **read-only at serve time** (written
  only by the ETL); runtime state (decisions, observations, 72 h posture, counterparty
  reputation) is read-write. `TTL = 72 h` mirrors posture decay.
- **Auth** — self-issued **HS256 JWT**, roles `CUSTOMER` / `APPROVER` / `OPS`, shared
  `DIAKRISIS_JWT_SECRET` validated by both services. Four-eyes = `role == APPROVER && sub != initiator`.

### Maven reactor

```
diakritis/                 (reactor root)
├── common/                shared DTOs (records), JWT, DynamoDB beans + bootstrap
├── engine/                decision pipeline: signals, typologies, bands, M1 loader  (library)
├── etl/                   Berka → DynamoDB feature tables + demo seed  (plain-Java CLI)
├── decision-service/      Spring Boot app :8081  (depends common + engine)
└── bank-app/              Spring Boot app :8080  (depends common; REST → decision-service)
```

**Stack:** Java 26 · Spring Boot 4.1.0 · Maven · Jackson (records as DTOs, snake_case) ·
[Smile](https://haifengl.github.io/) 3.1.1 (pure-JVM GradientTreeBoost) · AWS SDK v2 DynamoDB
Enhanced Client · jjwt (HS256) · commons-csv · JUnit 5. Virtual threads enabled.

---

## The decision engine

Signals are pure functions `(ActionEvent, FeatureStore, RuntimeState, Posture, Observations) →
[0,1]`. `score = Σ weight·value`, clipped 0–100. One hand-set weight table (regulatory
explainability; per-bank calibration roadmap). Bands: `ALLOW 0–29 · CONFIRM 30–59 · HOLD 60–84 ·
BLOCK 85+`; instant/P2P rails shift the edges −8. Non-monetary actions cap at `CONFIRM`.

**Typologies** are executable composites that pin the outcome (single match → `HOLD`; two+
matches and raw ≥ 85 → `BLOCK`): safe-account scam, invoice redirection, romance/repeat victim,
liquidation kill-chain, mule fan-out, payroll redirection, purchase scam.

**M1** — a Smile GradientTreeBoost trained on IEEE-CIS card-fraud (loaded, never trained here),
isotonic-calibrated then percentile-ranked, weight-capped at 18. **Resilience principle:** with
the ML weights at 0 (or the model unloadable), the deterministic engine stays fully functional —
that is failure isolation, not simplicity.

**AI co-judge** (advisory, champion/challenger): a second scorer runs in parallel and may
*add* one notch of friction (capped at `HOLD`) on a high-confidence stricter dissent — it can
never make the engine softer and never hard-blocks. `combined.decision == engineVerdict.decision`
except that single bounded escalation. Every verdict carries the signals + typologies that drove
it and an ISO 20022 reason code (auditability is a feature).

---

## Build & run

Prerequisites: JDK 26, Maven 3.9+, Docker.

```bash
# 1. DynamoDB Local
docker compose up -d dynamodb

# 2. Build the reactor
mvn clean package

# 3. Seed feature tables + demo from real Berka history
java -jar etl/target/etl-*.jar \
     --berka-dir data/raw/berka --ddb-endpoint http://localhost:8000 --demo

# 4. Run the services (set a >=32-char secret)
export DIAKRISIS_JWT_SECRET='change-me-to-a-32-byte-minimum-secret!!'
java -jar decision-service/target/decision-service-*.jar    # :8081
java -jar bank-app/target/bank-app-*.jar                    # :8080
```

The engine loads its pre-trained model artifacts from `diakrisis.models-dir`
(default `../diakrisis-models`).

### Building services independently

Every module is a **self-contained Maven project** — each service compiles, tests, and
packages on its own without the reactor. `common` and `engine` are versioned libraries
(`com.cy.diakritis:common` / `:engine` `0.1.0-SNAPSHOT`) installed to the local `.m2`; the
three deployables (`decision-service`, `bank-app`, `etl`) inherit from
`spring-boot-starter-parent` (or no parent, for `etl`), declare their own properties
(`java.version=26`) and their own `dependencyManagement`, and resolve `common`/`engine` from
`.m2` as ordinary dependencies. The root `pom.xml` is an **optional convenience aggregator**
only — it is never required to build a single service.

**Build order** — install the two libraries to `.m2` first, then build any service alone:

```bash
# 1. Publish the libraries to ~/.m2 (run from the reactor root).
#    Either via the reactor:
mvn -q -pl common,engine -am install
#    …or each library on its own (equivalent):
cd common && mvn -q clean install && cd ..
cd engine && mvn -q clean install && cd ..

# 2. Build each service ON ITS OWN (each cd is a standalone build, no sibling modules needed):
cd decision-service && mvn -q clean package   # -> BUILD SUCCESS, runnable fat jar
cd bank-app         && mvn -q clean package   # -> BUILD SUCCESS, runnable fat jar
cd etl              && mvn -q clean package    # -> BUILD SUCCESS, shaded runnable jar (target/etl.jar)

# Re-run the decision-service golden path standalone (DynamoDB Local must be up):
cd decision-service && mvn -q test             # T1–T15 + CI invariants green
```

A convenience script `./build.sh` runs the full order (libs to `.m2`, then each service
standalone). Run `./build.sh --help` for options.

### Try it

```bash
TOKEN=$(curl -s localhost:8080/auth/login -H 'Content-Type: application/json' \
        -d '{"username":"customer-A","password":"demo"}' | jq -r .token)

curl -s localhost:8081/decision -H "Authorization: Bearer $TOKEN" \
     -H 'Content-Type: application/json' -d @qa/examples/t3.json | jq '.engine_verdict'
```

---

## Golden path (T1–T6)

The acceptance suite (`qa/golden-path-scenarios.md`) pins exact verdicts through
`POST /decision`:

| Case | Scenario | Expected |
|---|---|---|
| T1 | established bill payee | `ALLOW` + SCA-exempt |
| T2 | standing-order rent payee | `ALLOW` + SCA-exempt |
| T3 | anomalous amount to a known payee | `CONFIRM` |
| T4 | invoice redirection (known supplier, changed ref) | `HOLD` · invoice_redirection |
| T5a | term-deposit break | `CONFIRM` + purpose (never HOLD/BLOCK) |
| T5b | kill-chain drain 20 min later | `HOLD` · liquidation_kill_chain |

T5a → T5b is the emotional centre: the deposit break sails through with a gentle question;
twenty minutes later the drain transfer slams into a `HOLD` **because the engine remembered**.

---

## Data provenance (stated honestly)

No public corpus of labelled APP-scam transfers exists — banks cannot publish them. So:

- **Legitimate-behaviour baselines are real:** Berka (PKDD'99), 1,056,320 rows, 208,283 outgoing
  transfers, 6,064 distinct counterparties. T1–T3 and T4's payment history run on **real account
  histories** (e.g. account `7819`'s counterparty `CD|46939146`: 60 payments of €129.60).
- **The fraud-trained ML signal is real:** IEEE-CIS card fraud (M1).
- **Three disclosed constructs**, only where Berka has no such data, each marked
  `source="CONSTRUCTED"`: the T4 confirmation-of-payee *name*, the T5 *term deposit*, and the T6
  *escalation* sequence. Berka has no counterparty names, no deposit product, and only flat
  standing orders.

Same epistemic position as every vendor — the difference is saying it first.

### Resolved policy decisions (named constants, not silent defaults)

No-approver `HOLD` stays `HOLD` · Ty2 "established" = ≥ 3 payments & ≥ 30 days · `LOCKED` is
terminal until an explicit ops release (pre-expiry release → `409 LOCKED_PRE_EXPIRY`) · salami
window = rolling 24 h · X1 cross-account reputation decays with a 6 h half-life ·
`AI_ESCALATION_THRESHOLD = 80`.

---

## Scope

This build delivers the **backend decision path** end-to-end (engine, both services, ETL, and the
T1–T6 golden-path tests on real Berka history). The **React mini-bank + ops dashboard** (React 18
+ TypeScript + Vite + Tailwind + Recharts) and the optional **Gemma co-judge** / **Qdrant M2**
vector path are roadmap — designed for behind the same interfaces, absent rather than stubbed.

Pre-trained model artifacts live in the sibling `diakrisis-models/` and are **loaded, never
retrained**.
