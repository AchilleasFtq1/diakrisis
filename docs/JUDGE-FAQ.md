# Diakrisis — questions you'll get asked (and the answers)

A cheat-sheet for demoing the platform. Short, honest answers.

---

## The product

**Q: What is Diakrisis in one sentence?**
A real-time risk-decision API a bank calls *before* it executes any customer action — it returns
one of five graduated outcomes and, crucially, **names the scam to the victim at the moment of
payment**.

**Q: How is this different from the fraud detection banks already have?**
Incumbents score *transactions* for known-bad patterns and mostly catch card fraud after the fact.
Diakrisis owns the layer they leave to the bank: the **customer-facing decision** on
**authorised-push-payment (APP) scams** — where the genuine customer, on genuine credentials,
is socially-engineered into paying a criminal. There's no stolen card to flag; **the sequence is
the scam**. We score the sequence and intervene in-flow.

**Q: What are the five outcomes?**
A friction ladder, proportional to risk: **ALLOW** (silent, SCA-exempt) → **CONFIRM** (step-up
auth + one tap) → **REQUIRE_APPROVAL** (a second person / the call-center) → **HOLD** (freeze +
name the scam + cancel) → **BLOCK** (stopped, fraud review). Friction appears only when risk
justifies it, and disappears when it doesn't.

**Q: Why show the customer a scam warning — doesn't that tip off the fraudster?**
In an APP scam the person at the keyboard **is the victim**, not the criminal (the criminal is on
the phone coaching them). Naming the scam — "this looks like a safe-account scam" — at the point of
payment **breaks the manipulation**. If instead it were account-takeover (criminal in the app), a
HOLD still **freezes** the money, so "tipping off" costs nothing. Regulators (UK PSR) now mandate
this kind of in-flow warning because it measurably stops APP fraud.

**Q: SCA and HOLD both fight fraud — why both?**
They stop **different** attackers. **SCA step-up** (on CONFIRM) defeats *account takeover* — a
hijacked session can't pass a second factor. But SCA is useless against an APP scam because the
*real* victim passes it happily. **HOLD** is the APP-scam defence: it doesn't ask "are you you?",
it freezes the money and names the manipulation. Each layer covers the other's blind spot.

---

## How it decides

**Q: How does it actually decide?**
A weighted **combination** of explainable signals (Σ weight·value, clipped 0–100) plus executable
**typologies** (named scam patterns). **No single signal decides anything** — a new geo on a
familiar device is nothing; a new device **and** new payee **and** balance-drain **and** new geo
together is a block. <50 ms per decision.

**Q: The "kill-chain" — how does it remember the earlier steps?**
APP scams are prepared in steps (break a deposit, raise a limit, add a payee, then send). We keep
**posture state** per account (e.g. "funds freed in the last N days"). When the drain transfer
arrives, the engine sees the freed-funds posture from the earlier break and trips
`liquidation_kill_chain`. *Demoed live: break a deposit → then sweep to a new payee → BLOCK score
99, because the engine remembered the break.*

**Q: Can a scammer just retry until it goes through?**
No. Each new attempt is **re-scored against current state**, and the prior attempt is now in the
velocity/recency memory — so retries **stay flagged or escalate**, never weaken. (A literal replay
with the same event id is idempotent — it returns the stored decision, no double-charge.)

**Q: What's the ML model's role? What if it's wrong or missing?**
Two ML signals (M1 gradient-boosting, M2 nearest-neighbour over fraud exemplars) add a learned
fraud-*shape* signal, **weight-capped at ~15%**. The deterministic engine is the foundation (~85%).
With the ML at weight 0 or unloadable, **the engine is fully functional** — ML is a bonus, never a
dependency. There's also an optional LLM **co-judge** (Gemma via Ollama) that can only escalate,
is hard-timeout'd, and defaults to UNAVAILABLE — it can never block the decision path.

---

## Data & retraining

**Q: What data is this built on? Is it real?**
Honestly stated: legitimate-behaviour baselines are **real** (Berka PKDD'99 — 1,056,320 real
Czech-bank transactions). The ML fraud signal is **real** (IEEE-CIS card fraud). Exactly **three**
constructs are disclosed (`source=CONSTRUCTED`) where Berka has no such field: a confirmation-of-
payee name, a term deposit, and one escalation sequence. No public corpus of labelled APP-scam
transfers exists — same epistemic position as every vendor; the difference is we say it.

**Q: If I load my own bank's data, do I have to retrain?**
**No — you re-run the ETL.** The deterministic engine derives every signal *live* from whatever
data is loaded ("new counterparty", "anomalous amount", "funds freed") — it's data-driven, not
data-memorised. The ML is a pre-trained, capped add-on that keeps working unchanged. Retraining is
an *optional* enhancement, never a blocker.

**Q: Privacy / PII?**
Signals are explainable and derived; no PII is logged in decisions. Money is integer cents,
timestamps are epoch-millis, counterparties are keys. The customer-facing explanation is plain
English; the engine internals (score, codes) are bank-side only.

---

## Architecture & ops

**Q: What's the architecture?**
A fraud **product** = an **API gateway** (`:8080`, the single front door — JWT edge auth, CORS,
routing, aggregated Swagger) fronting three services: **decision-service** (`:8081`, the engine),
**ops-service** (`:8082`, analyst dashboard), **iam-service** (`:8083`, identity + admin). Store is
**DynamoDB**; the M2 vector backend is **Qdrant** (with an in-JVM KDTree fallback). Java 26 / Spring
Boot 4 / Maven. The **demo-bank** is a separate dummy consumer that calls the product over HTTP.

**Q: How is it secured?**
Self-rolled **HS256 JWT** (no Cognito — cost). The gateway verifies at the edge; each service
re-validates (defence in depth). Roles CUSTOMER / APPROVER / OPS / ADMIN. A customer token can only
get decisions **for its own account** (account-ownership enforced — 403 otherwise). Four-eyes
approvals require APPROVER or ADMIN and can't be self-approved. We ran an attack battery:
`alg:none`, tampered/wrong-secret tokens, cross-account, CORS, doc-proxy traversal — all rejected.

**Q: How does it scale / how fast?**
Stateless services behind the gateway, DynamoDB for the feature/runtime store, virtual threads.
Decision latency target **<50 ms** (the engine path is pure-JVM; ML + co-judge are off the hot
path or capped). Horizontal scale by adding service replicas.

**Q: False positives — won't friction annoy real customers?**
Friction is **proportional to risk** and **removed** when risk is low: ALLOW is silent and even
SCA-exempt. Only genuinely risky shapes get heavy friction. A feedback loop (cancel = confirmed
save, release-after-expiry = false positive) calibrates the weights per bank over time.

**Q: Where do I see the engine working (it's hidden in the bank)?**
By design the **customer** bank UI never shows a verdict (real banks don't). The engine decision —
score, signals, typologies, reason — is on the **ops side** (`ops-service`, reachable through the
gateway `/ops/*` and the aggregated Swagger at `:8080/swagger-ui.html`). That's the fraud-analyst
view you show the judges.
