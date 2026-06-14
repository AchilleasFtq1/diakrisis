# 🎬 Diakrisis — Demonstration Journal (run-book)

**Read this top-to-bottom once before you present. Follow the bold steps exactly.**
This supersedes `docs/DEMO-SCRIPT.md` (which is stale — it shows the old BLOCK outcome; the clean
kill-chain is now a **HOLD with a named scam warning**, the SDD's intended "names the scam" moment).

> The engine is **stateful by design** (it remembers velocity & posture). **Always reset before a
> demo** (step 1) so the accounts are pristine, or outcomes will drift.

---

## 1. Pre-flight — run ONE command right before you present

```bash
# native Ollama must be running for the AI co-judge (Gemma):  HOME=$HOME nohup ollama serve &
cd /Users/achilleaseftychiou/Documents/Projects/diakrisis
bash qa/prep-demo.sh        # resets + WARMS Gemma & the kill-chain path, prints the links
```

> ⚠️ **Why warm, not just `up`?** On a cold boot the FIRST live kill-chain looks broken even though
> it isn't: the co-judge shows **"UNAVAILABLE"** (Gemma's first call cold-loads ~10s) and latency
> shows **"over SLA"** (the kill-chain path JIT-compiles on first use, ~90–115 ms). `prep-demo.sh`
> drives a few throwaway decisions so your first REAL run shows the co-judge **agreeing** and a green
> **~30–45 ms** latency. **Re-run it right before presenting** — Gemma re-cools after a few idle minutes.
> *(If you only changed code, add `--build`: `cd diakritis && docker compose --profile demo up -d --build` first.)*

The script prints "DEMO-READY" + the links. To double-check by hand:
```bash
docker inspect diakrisis-demo-bank --format '{{.State.Health.Status}}'   # → healthy
docker logs diakrisis-decision-service 2>&1 | grep -i "co-judge LIVE"     # → AI co-judge LIVE: gemma4:e2b
```
✅ When `demo-bank: healthy` and `co-judge LIVE` you're ready. **acc-B is now pristine (dep-001 ACTIVE).**

---

## 2. The map — where & who

| What | URL | Use |
|---|---|---|
| 🏦 **Meridian (customer bank)** | http://localhost:9000 | the customer side — **never shows the engine** |
| 🛡️ **Fraud-Ops Console (analyst)** | http://localhost:5173 | the engine's reasoning — **show the judges this** |
| API gateway / Swagger | http://localhost:8080 · /swagger-ui.html | only if asked |

**Logins**
- **Bank (`:9000`)** — just click a customer card. **No password** (it's a demo bank; you're picking who to impersonate).
- **Ops console (`:5173`)** — `ops-user` / `demo` (analyst). Admin page: `admin` / `admin`.

**Seeded accounts** (only acc-A / -B / -C are clickable in the bank)

| Account | Owner card | Balance | What it's for |
|---|---|---|---|
| **acc-A** | customer-A | €4,500 | normal payments — has 2 **established payees**: *CD Supplier*, *KL Supplier* |
| **acc-B** | customer-B | €4,980 | ⭐ **the kill-chain** — holds term deposit **dep-001** (€5,000) |
| **acc-C** | customer-C | €9,000 | clean retail / business-approver scenarios (advanced) |

---

## 3. THE DEMO — run these acts in order

### 🎯 ACT 1 — "When you're safe, you don't even notice us" (ALLOW)
1. Bank → click **customer-A**.
2. **Payments → Pay a saved payee** → payee **CD Supplier**, amount **`120.00`**, scheme **SEPA** → **Review & send**.
3. ✅ **"Payment sent"** — instant, no friction. *Say:* "A normal payment to a known supplier — the engine
   cleared it in under 50 ms and the customer felt nothing. That's the point: friction only when it's earned."

### ⭐ ACT 2 — THE KILL-CHAIN (your money shot) — customer-B
> The scam: a victim is coached to **liquidate savings** then **sweep them to a "safe account."**
> The engine remembers the break seconds later and **pauses the sweep, naming the scam.**

1. Bank → click **customer-B**.
2. **Accounts** (left nav) → **Term deposits** → on **dep-001** click **Break**.
3. 🔐 You get **"Confirm it's really you"** (a security step-up). *Say:* "Even breaking the deposit earns a check."
   → type **any 6 digits** (`123456`) → **Confirm & send** → ✅ **"Payment sent"** (deposit broken, funds freeing).
4. **Payments → Pay someone new**:
   - Beneficiary name: **`Safe Account Ltd`**
   - IBAN: leave the pre-filled one (it's a brand-new payee)
   - Amount: **`4850.00`**
   - Scheme: **SEPA Instant**   ← *(use Instant — it's the realistic "sweep it now" rail)*
   - **Review & send**.
5. ⏸ **THE MOMENT** — the customer sees:
   > **"We've paused this payment to protect you"** · *Paused*
   > **"This looks like an account-liquidation scam: funds were just freed and are now being sent to a brand-new payee — we have paused it to protect you."**
   > and the **only** button is **Cancel payment**.

   *Say:* "The customer never sees a score or a code — just plain English that **names the scam** and a way out.
   The engine connected the deposit-break to the sweep **across two separate actions**, in real time."

### 🛡️ ACT 3 — Show the engine's brain (the analyst console)
1. Ops console (`:5173`) → sign in **ops-user / demo**.
2. **Overview**: point at the **p50 latency `~22ms · within < 50ms SLA`** (green) and the live feed.
3. Click the **acc-B `HOLD`** row (the drain). On the Decision detail, show:
   - **Score 89 · HOLD** (a single typology pins it to HOLD, not BLOCK — graduated, not blunt).
   - **⛓ KILL-CHAIN DETECTED · `liquidation_kill_chain`** + the **timeline** (break → drain) and **"€… freed in last 72h"**.
   - **Engine `HOLD` vs AI co-judge (Gemma) `HOLD` · ✓ agree** — *"a deterministic engine, the ML model, and a local LLM all concur."*
   - **Contributing signals**: **M1 (ML) +17**, K1 (liquidation), B1 (new payee), **D1 +0** (device recognised) — *"explainable, every point accounted for."*
   - **Customer-facing explanation** = the exact scam-warning the customer saw.

*That's the whole pitch in one screen: real-time, explainable, hybrid (rules + ML + AI), customer-safe.*

---

## 4. Advanced / only-if-asked

- **Graduated friction (CONFIRM):** customer-A → Pay a saved payee → CD Supplier, amount **`700.00`** (vs the
  ~€130 norm) → 🔐 **step-up** (any 6 digits) → executes. Shows the middle tier between ALLOW and HOLD.
- **Four-eyes approval (REQUIRE_APPROVAL):** business mass-payments on an account with a *designated* approver
  go to an approval queue; only a **designated** approver (`approver-biz`) can sign off, **never the initiator**,
  and — as of this build — **not just any approver**. This is robust in the test suite; for a live click-through
  it needs a payroll batch on a designated-approver account, so prefer to **describe** it (or show the ops-console
  **Approvals** page) rather than build the batch on stage.
- **C3 "retry pressure":** if a coached victim **re-submits the same transfer at rising amounts in one session**,
  the engine now escalates (verified: €1k→€2k→€3k drove HOLD→BLOCK). Mention it; don't script it live.

---

## 5. ⛔ DON'T-FUCK-UP CHECKLIST

- [ ] **Reset first** (`down -v && up`). acc-B's dep-001 must be **ACTIVE**. If you already broke it this
      session, the drain will **BLOCK** (over-liquidated) instead of the cleaner **HOLD** — reset.
- [ ] **Ollama is running** before you start (or the co-judge shows UNAVAILABLE — not fatal, but the
      "all three agree" line is weaker). Check `co-judge LIVE` in the logs.
- [ ] Kill-chain order is **break the deposit FIRST, then drain**. The break is what arms the trap.
- [ ] Drain must go to a **NEW payee** (Pay someone *new*), not a saved one, and use **SEPA Instant**, amount **4850**.
- [ ] Step-up box: **any 6 digits** work (`123456`). There's no real phone — the *friction* is the point.
- [ ] Show the **analyst console (`:5173`)** for the verdict — the **bank UI deliberately never shows scores/codes.**
- [ ] One kill-chain per reset. To run it again, **reset** (it's a 60s `down -v && up`).

## 6. If it goes sideways
- **Drain BLOCKs instead of HOLDs** → acc-B was already drained/over-broken → **reset**.
- **Bank page won't sign in** (rare, after a restart) → reload `http://localhost:9000/sign-out` then the home page.
- **Co-judge says UNAVAILABLE** → start Ollama (`HOME=$HOME nohup ollama serve &`), wait ~15s for the model to warm, retry. The engine verdict is unaffected — it's advisory.
- **Feed looks empty** in the ops console → you haven't driven a payment yet; it fills as you demo.

## 7. One-line reset (between runs)
```bash
cd /Users/achilleaseftychiou/Documents/Projects/diakrisis/diakritis && \
  docker compose --profile demo down -v && docker compose --profile demo up -d
```
First saved-payee payment after a reset = **ALLOW**. You're clean.

---

### The 30-second "why we win"
*"Diakrisis is a real-time fraud-decision engine that catches **scams across actions, not just single
transactions** — it linked a deposit break to a follow-on sweep and paused it, **naming the scam to the
victim in plain English** with no codes. It's **hybrid** — a deterministic, fully-explainable rules engine,
an ML model, and a local LLM co-judge that all agree — and it decides in **under 50 ms**. The bank UI never
leaks a score; the analyst console shows every signal that fired."*
