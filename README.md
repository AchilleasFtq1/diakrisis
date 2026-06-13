<p align="center">
  <img src="docs/diakrisis-logo.svg" width="440" alt="Diakrisis — real-time scam decision engine"/>
</p>

<p align="center">
  <b>A real-time risk-decision API a bank calls <i>before</i> executing any customer action.</b><br/>
  <sub>Java 26 · Spring Boot 4.1 · DynamoDB · Smile (pure-JVM GBT) · Qdrant k-NN · Gemma co-judge · React 19 ops console</sub>
</p>

---

Diakrisis is a **hybrid decision system** — a deterministic, explainable engine **fused with machine
learning and a local LLM co-judge**. It scores a customer action from explainable signals
(counterparty intelligence, amount behaviour, velocity, geo/device context, and **kill-chain posture**
— what this account did in the last 72h), blends in **two ML signals** (a calibrated gradient-boosted
fraud model and vector similarity to known fraud exemplars), runs an **AI co-judge** (a local Gemma
LLM) in parallel as an independent second opinion, and returns one of **five graduated,
pattern-specific outcomes**. It does not try to out-detect the incumbents; it owns the layer they
leave to the bank: the **customer-facing decision itself**.

> Authorized-push-payment (APP) scams defeat binary fraud checks structurally: the genuine customer,
> on genuine credentials, authorizes every step — including the *preparatory* ones (break the deposit,
> raise the limit, add the payee, then send). Each step looks unremarkable in isolation.
> **The sequence is the scam.** Diakrisis scores the sequence, names the pattern to the victim at
> decision time, and adds friction *proportional to risk* — removing it when risk is low.

## Five graduated outcomes

Each verdict carries the **friction the bank must apply** (returned as `friction` + `sca_required`
on the verdict, so the bank doesn't re-derive it). The rungs defeat **different** attackers:

| Outcome | `friction` | What the bank does | Stops which attacker |
|---|---|---|---|
| `ALLOW` | `NONE` | execute silently; **SCA-exempt** (PSD2 RTS Art.18 TRA) — the product working invisibly | — (low risk) |
| `CONFIRM` | `SCA_STEP_UP` | **step-up re-authentication** (`sca_required`) + a one-sentence, one-tap purpose prompt | the **hacker** — a hijacked session can't pass a second factor |
| `REQUIRE_APPROVAL` | `SECOND_APPROVAL` | park it; a second authorised person **or the call-center/admin phones the customer** to verify, then approves/rejects (initiator can't self-approve) | insider / high-value business |
| `HOLD` | `FREEZE_AND_WARN` | **freeze** (never auto-sends), name the scam pattern to the customer, one-tap cancel; release later needs an extra check | the **APP scammer** — the victim authenticates genuinely, so only naming + freeze works |
| `BLOCK` | `STOP_AND_REVIEW` | stop and route to manual fraud review | high-confidence fraud |

No single signal decides anything — the verdict is a weighted **combination** (Σ weight·value,
clipped 0–100) plus executable **typologies**. A new geo on a familiar device is nothing; a new
device **and** new payee **and** drain **and** new geo together is a block.

## Hybrid by design — deterministic engine + ML + AI

Three layers, deliberately ordered so the system stays explainable and resilient:

- 🧮 **Deterministic engine** *(authoritative)* — **27 explainable signals** across 11 families + **7
  executable typologies** → a weighted score and one of five outcomes. Every decision is fully
  auditable; the golden-path tests pin it to exact verdicts.
- 🤖 **Machine learning** *(additive signals)* — **M1**: a [Smile](https://haifengl.github.io/)
  GradientTreeBoost fraud model trained on IEEE-CIS, isotonic-calibrated so its output is a real
  probability; **M2**: cosine **k-NN over a Qdrant** store of fraud exemplars (*"this most resembles
  14 confirmed fraud cases"* — retrieval-grounded explainability, with an in-JVM KDTree fallback).
  Both are **capped and additive**: if the ML weights go to zero the deterministic engine still works
  (resilience, not simplicity).
- 🧠 **AI co-judge** *(advisory)* — a **local Gemma 4 LLM** runs **in parallel** as an independent
  second scorer (data never leaves the box). It can add **exactly one capped band of friction**
  (PASS→CONFIRM, CONFIRM→HOLD) — **never block, never relax** — and its disagreement flags edge cases
  for a human. It never moves the authoritative verdict; the engine is champion of record.

The split is the point: **ML/AI sharpen the engine, they don't replace it.** Both ML signals or the
whole co-judge can fail (timeout, OOM, container down) and the engine returns a correct, explainable
decision unchanged.

## System architecture

Every external call enters through the **gateway**; the backend services publish no host port.

```
  ┌────────────────────┐        ┌──────────────────────────┐
  │  demo-bank (:9000) │        │  ops-console (:5173)      │   ← clients
  │  Thymeleaf bank    │        │  React 19 analyst console │
  └─────────┬──────────┘        └────────────┬─────────────┘
            │  HTTPS + Bearer JWT             │
            └───────────────┬────────────────┘
                            ▼
                ┌───────────────────────────┐
                │   api-gateway  (:8080)     │  single front door
                │   Spring Cloud Gateway MVC │  edge JWT verify (401/403),
                │   CORS, aggregated Swagger │  /admin/** → ADMIN
                └───────┬───────┬───────┬────┘
          /decision     │       │       │   /ops/**
          /actions/**   │       │       │
                        ▼       ▼       ▼
        ┌───────────────────┐ ┌──────────────┐ ┌────────────────────┐
        │ decision-service  │ │ iam-service  │ │   ops-service      │
        │      (:8081)      │ │   (:8083)    │ │      (:8082)       │
        │ engine ‖ co-judge │ │ JWT mint,    │ │ read projections   │
        │ lifecycle, posture│ │ users, admin │ │ (feed/approvals/…) │
        └───┬───────────┬───┘ └──────┬───────┘ └─────────┬──────────┘
            │           │            │                   │
            ▼           ▼            ▼                   ▼
   ┌────────────┐ ┌──────────┐  ┌──────────────────────────────┐
   │  Qdrant    │ │  Ollama  │  │   DynamoDB Local (:8000)      │
   │ (:6334) M2 │ │(:11434)  │  │ decisions · cases · posture · │
   │ exemplars  │ │ Gemma 4  │  │ observations · reputation ·   │
   │ [profile   │ │ co-judge │  │ users · outcomes              │
   │   m2]      │ │[native/  │  └──────────────────────────────┘
   └────────────┘ │ llm]     │
                  └──────────┘
        engine library (signals · typologies · bands · M1 Smile GBT · M2 index · combine rule)
        is compiled into decision-service; common holds the shared DTO/JWT/Dynamo contract.
```

### Components

| Module | Port | Responsibility |
|---|---|---|
| **`api-gateway/`** | **8080** | The single front door. Routes by path, verifies the HS256 bearer at the edge, gates `/admin/**` to ADMIN, CORS, and hosts the **aggregated Swagger UI** (proxies each backend's `/v3/api-docs`). |
| **`decision-service/`** | 8081¹ | The decision API — `POST /decision`, action lifecycle (`/actions/{id}/…`), engine + Gemma co-judge orchestration, idempotent decision store, posture/observation/reputation commits. |
| **`iam-service/`** | 8083¹ | Identity + admin — register / login / refresh / logout, persistent users (BCrypt), the ADMIN-only user-management console. **Mints** every JWT the others verify. |
| **`ops-service/`** | 8082¹ | Analyst/approver read-side — feed, counters, approvals, decision detail, account posture, **beneficiary (mule) intelligence**, outcomes board. |
| `engine/` | — | Pure library: the decision pipeline (signals, typologies, bands), the Smile **M1** loader, the KDTree/Qdrant **M2** index, the §8.3 combine rule. Testable without a web server. |
| `common/` | — | The cross-service contract: DTO records (`ActionEvent`, `Decision`, …), JWT helpers, DynamoDB item beans + config. |
| `etl/` | — | Offline CLI: streams Berka history into the feature tables + writes the demo seed + loads Qdrant exemplars. Not on the hot path. |
| **`demo-bank/`** | **9000** | A standalone Spring Boot + Thymeleaf bank (SQLite). Builds an `ActionEvent` from its own facts and calls the product **through the gateway** before executing — a real bank's integration. |
| **`ops-console/`** | **5173** | The React 19 analyst console (Vite · Tailwind v4 · TanStack Query). Live feed, signal breakdown, kill-chain timeline, four-eyes approvals, mule view, outcomes, admin, **signal Reference page**. |

<sub>¹ internal only — in Docker these publish no host port; reach them through the gateway at `:8080`.</sub>

### Tech stack

| Layer | Choice |
|---|---|
| Language / runtime | **Java 26** (services) · **TypeScript** (console) |
| Frameworks | Spring Boot 4.1 · Spring Cloud Gateway Server MVC · Thymeleaf (demo-bank) · React 19 + Vite 6 |
| Build | Maven multi-module reactor (`diakritis/`) · Vite (`ops-console/`) |
| Persistence | **DynamoDB Local** (decisions, posture, observations, reputation, users, outcomes) · SQLite (demo-bank + ETL feature store) |
| ML | **Smile** GradientTreeBoost (M1, isotonic-calibrated, pure JVM) · **Qdrant** cosine k-NN exemplars (M2, in-JVM KDTree fallback) |
| AI co-judge | **Gemma 4** (`gemma4:e2b`) via Ollama (advisory, time-boxed, can only escalate one band) |
| Auth | HS256 JWT (jjwt) · BCrypt · rotating refresh tokens · roles CUSTOMER / APPROVER / OPS / ADMIN |
| UI | Tailwind v4 · TanStack Query v5 · React Router v7 · lucide-react |
| Tests | JUnit 5 + MockMvc — golden path T1–T15 pinned to exact verdicts |

## The decision flow

```
POST /decision  (ActionEvent + Bearer)
      │
      ▼  engine.score(event)                 ◄── AUTHORITATIVE, deterministic, <50ms target
      │     resolve counterparty identity → 27 signals (Σ weight·value, clip 0–100)
      │     → typologies → band → REQUIRE_APPROVAL routing → SCA exemption → templates
      │
      ║  ‖  aiCoJudge.score(event, signals)  ◄── ADVISORY, time-boxed, local Gemma
      │
      ▼  combine(engine, ai)   §8.3: the AI can add exactly one capped band of friction
      │                        (PASS→CONFIRM, CONFIRM→HOLD) — never BLOCK, never relax.
      ▼
   Decision { engine_verdict (authoritative) · ai_co_judge · combined · lifecycle ·
              explanation{customer, audit} · reason_code (ISO 20022) · latency_ms }
   ── idempotent on event_id; HELD/BLOCKED/cancelled writes cross-account reputation (X1 moat) ──
```

Lifecycle (holds never auto-execute; the initiator can't approve their own action):

```
DECIDED ─ ALLOW ───────────► EXECUTED
        ─ CONFIRM ──────────► PENDING_CONFIRM ─ confirm ► EXECUTED | abandon ► ABANDONED
        ─ REQUIRE_APPROVAL ─► PENDING_APPROVAL ─ approve(≠initiator) ► EXECUTED | reject ► REJECTED
        ─ HOLD ─────────────► HELD ─ cancel ► CANCELLED | release(after expiry) ► EXECUTED
        ─ BLOCK ────────────► REVIEW
```

## The signal model

The verdict is a weighted sum of **27 signals** across 11 families, plus 7 named typologies. The
console's **Reference** page (`/reference`) documents every code; in short:

`B*` beneficiary/counterparty · `P1` alias re-point (SIM-swap) · `A*` amount behaviour · `V*` velocity ·
`C*` channel & time · `G*` geo · `D*` device · `K*` 72h kill-chain posture · `MP*` mass-payment batch ·
`M1/M2` ML model + vector-neighbour · `X1` **cross-account reputation** (the network moat — a payee
flagged on *another* account warns the next victim).

**Typologies** (a single match → HOLD, two+ → BLOCK): `liquidation_kill_chain`, `safe_account_scam`,
`invoice_redirection`, `purchase_scam`, `vulnerability_escalation`, `payroll_redirection`,
`mule_fan_out`.

## Quick start

**Prerequisites:** Docker + Docker Compose, JDK 26 (only for local per-jar runs), Node 20+ (only for
the ops console). Optional: a native [Ollama](https://ollama.com) with `gemma4:e2b` for the co-judge.

### 1 — The product (one command)

```bash
cd diakritis
docker compose up --build            # dynamodb → etl seed → decision/iam/ops → api-gateway
```

Everything is reached through the gateway at **http://localhost:8080** (aggregated Swagger at
`/swagger-ui.html`). Optional profiles:

```bash
docker compose --profile demo up --build   # + the demo bank (:9000)
docker compose --profile m2   up -d qdrant # live Qdrant M2 backend (:6334)
docker compose --profile llm  up -d ollama # Gemma co-judge in Docker (CPU)
```

> **Co-judge note:** for a fast co-judge, run Ollama **natively** (Metal/GPU) rather than the `llm`
> profile (Docker is CPU-only): `HOME=$HOME ollama serve & ollama pull gemma4:e2b`, then point
> decision-service at `http://host.docker.internal:11434`.

### 2 — The ops console (React)

```bash
cd ops-console
npm install
npm run dev                          # http://localhost:5173  (calls the gateway at :8080)
```

### 3 — Local per-jar (development)

```bash
cd diakritis
docker compose up -d dynamodb                       # DynamoDB Local :8000
mvn clean package                                   # build everything (Java 26)
java -jar etl/target/etl.jar \
     --berka-dir ../data/raw/berka --ddb-endpoint http://localhost:8000 --demo
export DIAKRISIS_JWT_SECRET='change-me-to-a-32-byte-minimum-secret!!'
java -jar iam-service/target/iam-service-*.jar              # :8083 (mints tokens)
java -jar decision-service/target/decision-service-*.jar    # :8081
java -jar ops-service/target/ops-service-*.jar              # :8082
java -jar api-gateway/target/api-gateway-*.jar              # :8080 (front door)
```

## Test users

Seeded at IAM boot (`DemoUserSeeder`). All passwords are `demo` **except** `admin`.

| Username | Password | Role | Account | Use it for |
|---|---|---|---|---|
| `ops-user` | `demo` | OPS | — | the **ops console** (feed, decisions, mule view, outcomes) |
| `admin` | `admin` | ADMIN | — | ops console **+ user management** (`/admin`) |
| `approver-biz` | `demo` | APPROVER | — | the **four-eyes** approval queue |
| `customer-A` | `demo` | CUSTOMER | `acc-A` | the **demo bank** (clean / low-risk flows) |
| `customer-B` | `demo` | CUSTOMER | `acc-B` | the demo bank (the **kill-chain** account) |
| `customer-C` | `demo` | CUSTOMER | `acc-C` | the demo bank |
| `customer-vuln` | `demo` | CUSTOMER | `acc-V` | vulnerable-customer flows |

## Try it

```bash
# 1) get a token
TOKEN=$(curl -s -X POST http://localhost:8080/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"customer-B","password":"demo"}' | jq -r .token)

# 2) score an action (a transfer to a brand-new payee)
curl -s -X POST http://localhost:8080/decision \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"event_id":"demo-1","account_id":"acc-B","event_type":"TRANSFER",
       "context":{"ts":"2026-06-13T22:00:00Z","session_id":"s1","channel":"MOBILE_APP",
                  "ip":"203.0.113.7","device":{"device_id":"dev-b","platform":"IOS"}},
       "payload":{"counterparty":{"addressing":"IBAN","value":"CY99...","display_name":"New Payee"},
                  "amount_eur":8400.00,"available_balance_eur":9000.00,"rail":"INSTANT"}}' | jq .combined
```

Then explore the UIs:
- **Demo bank** → http://localhost:9000 (sign in as `customer-B` / `demo`)
- **Ops console** → http://localhost:5173 (sign in as `ops-user` / `demo` or `admin` / `admin`)
- **Swagger** → http://localhost:8080/swagger-ui.html

## Repository layout

```
diakrisis/
├── diakritis/         the PRODUCT — Maven reactor (api-gateway + decision/iam/ops + engine/common/etl)
├── demo-bank/         standalone DUMMY bank (SQLite + Thymeleaf) that calls the product over HTTP
├── ops-console/       the React 19 analyst console
├── diakrisis-models/  pre-trained M1 artifacts — loaded at boot, never retrained
├── data/              raw datasets (Berka behavioural history, IEEE-CIS, ULB) — not product code
├── docs/              the SDD (diakrisis-sdd-v4.pdf) + design notes + this logo
└── qa/                the golden-path T-case specs
```

## Testing & the golden path

`POST /decision` is pinned to exact verdicts (`qa/golden-path-scenarios.md`, T1–T15): established
payees → `ALLOW` + SCA-exempt · anomalous amount → `CONFIRM` · invoice redirection, kill-chain
drain, alias re-point, salami, cross-account moat → `HOLD` · stacked signals / mule fan-out →
`BLOCK` · payroll batch → `REQUIRE_APPROVAL` with the bad line quarantined · self-approval → `403`.
The kill-chain pair is the centre: a drain is held **because the engine remembered** the earlier
deposit break.

```bash
cd diakritis && mvn test          # full suite incl. the golden path
```

## Data provenance (stated honestly)

No public corpus of labelled APP-scam transfers exists. So legitimate-behaviour baselines are
**real** (Berka PKDD'99 — 1,056,320 rows, 208,283 outgoing transfers), the fraud-trained ML signal
is **real** (IEEE-CIS card fraud), and exactly **three constructs** are disclosed
(`source="CONSTRUCTED"`) only where Berka has no such data: the confirmation-of-payee *name*, the
*term deposit*, and the *escalation* sequence. Same epistemic position as every vendor — the
difference is saying it first.

## Design document

The full Solution Design Document (v4) — domain model, signal catalog, scoring/bands, co-judge
reconciliation (§8.3), API contract, demo script, and AWS production path — is in
[`docs/diakrisis-sdd-v4.pdf`](docs/diakrisis-sdd-v4.pdf).

---

<sub>Diakrisis · iFX Hack 2026 · built live, local-first, real public datasets only.</sub>
