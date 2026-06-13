# Diakrisis

**A real-time risk-decision API a bank calls *before* executing any customer action.**

Diakrisis scores a customer action from explainable signals — counterparty intelligence,
amount behaviour, velocity, geo/device context, and **kill-chain posture** (what this account
did recently) — and returns one of five graduated, pattern-specific outcomes. It does not try
to out-detect the incumbents; it owns the layer they leave to the bank: the **customer-facing
decision itself**.

> Authorized-push-payment (APP) scams defeat binary fraud checks structurally: the genuine
> customer, on genuine credentials, authorizes every step — including the *preparatory* ones
> (break the deposit, raise the limit, add the payee, then send). Each step looks unremarkable.
> **The sequence is the scam.** Diakrisis scores the sequence, names the pattern to the victim
> at decision time, and adds friction *proportional to risk* — removing it when risk is low.

## Five graduated outcomes

| Outcome | Friction |
|---|---|
| `ALLOW` | none (SCA-exempt where PSD2 RTS Art. 18 TRA permits) — the product working silently |
| `CONFIRM` | one specific sentence + one tap (purpose prompt on liquidity actions) |
| `REQUIRE_APPROVAL` | a second authorized person must approve (four-eyes; initiator can't self-approve) |
| `HOLD` | cooling-off + scam-pattern-naming warning + one-tap cancel (never auto-executes) |
| `BLOCK` | stopped; manual review (rare by design) |

No single signal decides anything — the verdict is a weighted **combination** of signals
(Σ weight·value, clipped 0–100) plus executable typologies. A new geo on a familiar device is
nothing; a new device **and** new payee **and** drain **and** new geo together is a block.

## What's in here

This repo is two top-level pieces:

```
diakrisis/
├── diakritis/          all the code (see role grouping below)
└── diakrisis-models/   pre-trained M1 model artifacts — loaded at boot, never retrained
```

Inside `diakritis/`, the code is organized by **role**, not as one flat blob:

**Deployable services** — the things that actually run, each its own process / jar / container:
| Path | Port | Role |
|---|---|---|
| `decision-service/` | 8081 | the decision engine API — `POST /decision`, action lifecycle, signals, typologies, M1/M2, co-judge. Authoritative. Validates the user JWT. |
| `bank-app/` | 8080 | mini-bank (accounts, payees, transfer/P2P/batch, deposit-break) + ops dashboard + JWT issuer. Builds an `ActionEvent` and calls `decision-service` before executing, forwarding the caller's Bearer. |

**Shared libraries** — consumed by the services, not deployed on their own:
| Path | Role |
|---|---|
| `engine/` | the decision pipeline: signals, typologies, bands, the Smile M1 loader, the KDTree M2 index, the combine rule. Pure library — testable without a web server. |
| `common/` | the cross-service contract: DTO records (`ActionEvent`, `Decision`, …), JWT (`JwtService`/filter), DynamoDB item beans + config. Shared so the two services never drift. |

**Tooling & data:**
| Path | Role |
|---|---|
| `etl/` | offline CLI — streams Berka history into the DynamoDB feature tables and writes the demo seed. Not on the hot path. |
| `data/` · `docs/` · `qa/` | Berka/IEEE-CIS raw data · the SDD · the golden-path T-case specs |

> **Build model:** each **service builds and runs independently** — its own jar, its own Docker
> image — depending on `common`/`engine` as ordinary library artifacts. The top-level
> `diakritis/pom.xml` is a *convenience aggregator* that builds everything at once; it is **not**
> required to build a single service (`cd decision-service && mvn package` stands alone).
> `diakritis/README.md` has the per-module + engine detail.

**Stack:** Java 26 · Spring Boot 4.1.0 · Maven · Jackson (records) · Smile 3.1.1 (pure-JVM GBT +
KDTree) · AWS SDK v2 DynamoDB Enhanced Client · jjwt (HS256) · DynamoDB Local · JUnit 5.

## Build & run

```bash
cd diakritis
docker compose up -d dynamodb                       # DynamoDB Local :8000
mvn clean package                                   # build everything (Java 26)
java -jar etl/target/etl.jar \                      # seed from real Berka history
     --berka-dir data/raw/berka --ddb-endpoint http://localhost:8000 --demo
export DIAKRISIS_JWT_SECRET='change-me-to-a-32-byte-minimum-secret!!'
java -jar decision-service/target/decision-service-*.jar    # :8081
java -jar bank-app/target/bank-app-*.jar                    # :8080
```

The engine loads model artifacts from `diakrisis.models-dir` (default `../diakrisis-models`).
Full containerized run (`docker compose up --build`) and per-service standalone builds are
documented in `diakritis/README.md`.

## Golden path (T1–T15)

`POST /decision` is pinned to exact verdicts (`diakritis/qa/golden-path-scenarios.md`):
established payees → `ALLOW` + SCA-exempt · anomalous amount → `CONFIRM` · invoice redirection,
kill-chain drain, romance escalation, alias re-point, salami, cross-account moat → `HOLD` ·
stacked signals / mule fan-out → `BLOCK` · payroll batch → `REQUIRE_APPROVAL` with the bad line
quarantined · self-approval → `403`. The kill-chain pair is the centre: a drain is held
**because the engine remembered** the earlier deposit break.

## Data provenance (stated honestly)

No public corpus of labelled APP-scam transfers exists. So legitimate-behaviour baselines are
**real** (Berka PKDD'99 — 1,056,320 rows, 208,283 outgoing transfers), the fraud-trained ML
signal is **real** (IEEE-CIS card fraud), and exactly **three constructs** are disclosed
(`source="CONSTRUCTED"`) only where Berka has no such data: the confirmation-of-payee *name*,
the *term deposit*, and the *escalation* sequence. Same epistemic position as every vendor —
the difference is saying it first.

## Status

Backend: the decision engine (all signals + typologies), both services, the ETL, and the
golden-path tests on real Berka history. The optional layers (Gemma co-judge via Ollama, Qdrant
M2 backend, OpenAPI/Swagger, feedback counters, vulnerability-aware friction) and the React
mini-bank + ops dashboard are built on top of the same seams.
