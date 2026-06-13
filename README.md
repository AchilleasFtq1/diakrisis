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

The **product is the fraud-decision system**, not a bank. The bank is a throwaway test consumer
that lives outside the product to prove the integration. The repo keeps the product code
(`diakritis/`) cleanly separate from the data, specs, and the dummy consumer:

```
diakrisis/
├── diakritis/          the PRODUCT — the fraud-decision system (reactor: gateway + 3 services + libs)
├── demo-bank/          a standalone DUMMY bank (SQLite + MVC) that calls the product over HTTP
├── diakrisis-models/   pre-trained M1 model artifacts — loaded at boot, never retrained
├── data/               raw datasets (Berka behavioural history, IEEE-CIS, ULB) — not product code
├── docs/               the SDD + design notes
└── qa/                 the golden-path T-case specs
```

**The product (`diakritis/`)** — every external call enters through the gateway; the three
services behind it are not reachable directly (in Docker they publish no host port):

| Path | Port | Role |
|---|---|---|
| `api-gateway/` | **8080** | the single **front door**. Spring Cloud Gateway Server MVC: routes by path to the services, verifies the HS256 bearer at the edge (401/403), CORS, and hosts the **aggregated Swagger UI** (proxies each backend's `/v3/api-docs` so the whole UI works through :8080 alone). |
| `decision-service/` | 8081 | the decision engine API — `POST /decision`, action lifecycle, signals, typologies, M1/M2, co-judge. Authoritative; re-validates the JWT. |
| `ops-service/` | 8082 | analyst/approver dashboard — read-only OPS projections (feed, counters, approvals) over what decision-service writes. |
| `iam-service/` | 8083 | identity + admin — register/login/refresh/logout, persistent users (BCrypt), admin user-management. **Mints** the JWTs every service verifies. |

**Shared libraries** (consumed by the services, not deployed on their own):
| Path | Role |
|---|---|
| `engine/` | the decision pipeline: signals, typologies, bands, the Smile M1 loader, the KDTree/Qdrant M2 index, the combine rule. Pure library — testable without a web server. |
| `common/` | the cross-service contract: DTO records (`ActionEvent`, `Decision`, …), JWT, DynamoDB item beans + config. Shared so the services never drift. |

**Tooling:**
| Path | Role |
|---|---|
| `etl/` | offline CLI — streams Berka history (from the repo-root `data/`) into the DynamoDB feature tables and writes the demo seed. Not on the hot path. |

**The dummy bank (`demo-bank/`)** — a separate Spring Boot app (SQLite + Thymeleaf MVC, port
**9000**), **not** part of the reactor and depending on nothing from it. It builds an `ActionEvent`
from its own SQLite facts and calls the product **through the gateway** before executing — exactly
how a real bank would integrate. It exists only to exercise the product end-to-end.

> **Build model:** each product service builds and runs independently — its own jar, its own Docker
> image — depending on `common`/`engine` as ordinary library artifacts (the gateway and demo-bank
> depend on neither). The top-level `diakritis/pom.xml` is a *convenience aggregator*; it is **not**
> required to build a single service (`cd decision-service && mvn package` stands alone).
> `diakritis/README.md` has the per-module + engine detail.

**Stack:** Java 26 · Spring Boot 4.1.0 · Maven · Jackson (records) · Smile 3.1.1 (pure-JVM GBT +
KDTree) · AWS SDK v2 DynamoDB Enhanced Client · jjwt (HS256) · DynamoDB Local · JUnit 5.

## Build & run

**Containerized (one command — the whole product):**

```bash
cd diakritis
docker compose up --build              # dynamodb → etl --demo seed → decision/iam/ops → api-gateway
# add the dummy bank that drives it through the gateway:
docker compose --profile demo up --build
```

Everything is reached through the gateway at **`http://localhost:8080`** — aggregated Swagger at
`/swagger-ui.html`, `POST /auth/login` to get a token, `POST /decision` with the Bearer. The three
services publish no host port; the gateway is the only door. Optional profiles: `--profile llm`
(Gemma co-judge via Ollama), `--profile m2` (live Qdrant M2 backend). The dummy bank UI is at
`http://localhost:9000`.

**Local (per-jar, for development):**

```bash
cd diakritis
docker compose up -d dynamodb                       # DynamoDB Local :8000
mvn clean package                                   # build everything (Java 26)
java -jar etl/target/etl.jar \                      # seed from real Berka history
     --berka-dir ../data/raw/berka --ddb-endpoint http://localhost:8000 --demo
export DIAKRISIS_JWT_SECRET='change-me-to-a-32-byte-minimum-secret!!'
java -jar iam-service/target/iam-service-*.jar              # :8083 (mints tokens)
java -jar decision-service/target/decision-service-*.jar    # :8081
java -jar ops-service/target/ops-service-*.jar              # :8082
java -jar api-gateway/target/api-gateway-*.jar              # :8080 (front door)
```

The engine loads model artifacts from `diakrisis.models-dir` (default `../diakrisis-models`).
Per-service standalone builds and the full module detail are in `diakritis/README.md`.

## Golden path (T1–T15)

`POST /decision` is pinned to exact verdicts (`qa/golden-path-scenarios.md`):
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

The full product runs: the decision engine (all signals + typologies), the four services
(gateway + decision + ops + iam), the ETL, full JWT auth (persistent users, BCrypt, token
lifecycle, admin user-management), and the golden-path tests (T1–T15) green on real Berka history.
The optional layers — Gemma co-judge via Ollama, live Qdrant M2 backend, aggregated OpenAPI/Swagger
through the gateway, feedback counters, vulnerability-aware friction — are wired on the same seams.
The dummy `demo-bank` exercises the whole chain end-to-end through the gateway.
