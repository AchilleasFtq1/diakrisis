# Diakrisis — demo script & test values

Exactly what to type to make each outcome happen, plus the logins and the API equivalents.
**The engine is stateful** — for a clean demo run a reset first (see bottom).

> 📓 For a full presenter run-book (act-by-act click order + a don't-fuck-up checklist + talking
> points), see **[`DEMO-JOURNAL.md`](../DEMO-JOURNAL.md)** at the repo root. This file is the quick
> input/outcome reference.

---

## Where things are

| What | URL | Notes |
|---|---|---|
| **Demo bank (Meridian)** | http://localhost:9000 | the customer UI — never shows the engine |
| **Product gateway** | http://localhost:8080 | the only API door |
| **Aggregated Swagger** | http://localhost:8080/swagger-ui.html | all 3 services' APIs |
| **Engine / ops view** | http://localhost:8080/ops/feed | the fraud-analyst side (show judges) |

## Logins

- **Bank UI** (`:9000`): just click a customer card to sign in — no password (session).
- **APIs** (`:8080`, for ops/admin/Swagger "Authorize"): `POST /auth/login`
  | User | Password | Role |
  |---|---|---|
  | `customer-A` / `-B` / `-C` | `demo` | CUSTOMER |
  | `approver-biz` | `demo` | APPROVER |
  | `ops-user` | `demo` | OPS |
  | `admin` | `admin` | ADMIN |

## Demo accounts (seeded from real Berka history)

| Account | Owner | Balance | Has |
|---|---|---|---|
| **acc-A** | customer-A | €4,500 | two established payees: **CD Supplier**, **KL Supplier** |
| **acc-B** | customer-B | €4,980 | term deposit **dep-001** (€5,000) — the kill-chain seed |
| **acc-C** | customer-C | €9,000 | clean retail |

---

## The outcomes — exact inputs (verified live)

Sign into the bank, go to **Payments**.

### ✅ ALLOW → "Payment sent" (silent, SCA-exempt)
- customer-A → **Pay a saved payee** → **CD Supplier**, amount **120.00**, SEPA.
- Engine: `ALLOW`, score **6**. The product working invisibly. *(Must be an early payment — see reset.)*

### 🔐 CONFIRM → "Pending" + SCA step-up
- Either: customer-A → **Pay a saved payee** → CD Supplier, amount **700.00** (anomalous vs the
  ~€130 norm) → `CONFIRM`, score ~**41**.
- Or: customer-B → **Account** → Term deposits → **Break** dep-001 → `CONFIRM`, score **63**.

### ⏸ HOLD → "Paused" + named scam warning — the kill-chain (the money shot)
1. Sign in **customer-B** → **Accounts** → Term deposits → **Break dep-001** → step-up (any 6 digits,
   e.g. `123456`) → **Confirm & send** → "Payment sent". (The break itself is a `CONFIRM` — funds freed.)
2. → **Payments** → **Pay someone new** → beneficiary **`Safe Account Ltd`**, any new IBAN, amount
   **`4850.00`**, scheme **SEPA Instant**.
- Engine: **`HOLD`**, score **~89**, single typology **`liquidation_kill_chain`** (a single typology
  pins the verdict to HOLD — graduated, not a blunt BLOCK).
- The point: the engine **remembered the deposit break** seconds earlier and **paused the sweep,
  naming the scam**. Customer sees *"We've paused this payment to protect you"* +
  *"This looks like an account-liquidation scam…"* and only a **Cancel payment** button — no scores, no codes.

> ✅ On a **reset** account this is a clean **HOLD with the named warning** (the SDD "names the scam"
> moment). If you've already broken/drained acc-B this session it over-liquidates and escalates to
> **BLOCK** (a valid catch, but blunter) — **reset first** for the HOLD.

### Other pure-HOLD scenarios (pinned in the suite)
- Invoice-redirection / confirmation-of-payee mismatch, romance escalation, salami, alias re-point,
  cross-account moat — each produces `HOLD` with one typology + the named warning; verified in the
  golden-path test suite (T4, T6, T8–T10, T14).

### 👥 REQUIRE_APPROVAL → "Awaiting approval"
- A **business mass-payment** on an account with a designated approver (four-eyes). Verified in the
  suite (T11/T13/T11b): only the account's **designated** approver (`approver-biz`) — or an ADMIN
  (call-center) — can sign off; the initiator can't self-approve (→ 403 `SELF_APPROVAL_FORBIDDEN`),
  and **not just any approver** can either (→ 403 `APPROVER_NOT_DESIGNATED`). The seeded
  designated-approver accounts are `biz-0042` and `acc-C`; drive this via the API/test path.

---

## See the engine working (ops side)

The bank hides the verdict; the analyst view shows it:
```bash
GW=http://localhost:8080
OPS=$(curl -s -X POST $GW/auth/login -H 'Content-Type: application/json' \
      -d '{"username":"ops-user","password":"demo"}' | python3 -c "import sys,json;print(json.load(sys.stdin)['token'])")
curl -s -H "Authorization: Bearer $OPS" $GW/ops/feed | python3 -m json.tool   # decisions + scores + typologies
```

## API equivalents (drive it without the UI)

```bash
B=http://localhost:9000
# ALLOW  (the scripted /api surface names the customer in the body)
curl -s -X POST $B/api/transfer -H 'Content-Type: application/json' \
     -d '{"customer":"customer-A","account":"acc-A","payee":"CD|46939146","amount":120.00}'
# kill-chain → HOLD (paused + named scam warning) — break first, then drain to a NEW payee on INSTANT
curl -s -X POST "$B/api/deposits/dep-001/break?customer=customer-B"
curl -s -X POST $B/api/transfer-new -H 'Content-Type: application/json' \
     -d '{"customer":"customer-B","account":"acc-B","iban":"LU00DRAIN0001","resolvedName":"Z","amount":4850.00,"rail":"INSTANT"}'
```
(The `/api/*` responses include the full engine verdict — that's the analyst view, not the bank UI.)

---

## ⚠️ Reset between demos (the engine is stateful, on purpose)

Each payment is remembered (velocity, posture). After a few payments, a normal transfer may
escalate — that's the engine working, but it means the "first payment = ALLOW" trick needs a clean
slate. To reset (wipes in-memory DynamoDB + the bank's SQLite and re-seeds Berka):
```bash
cd diakritis
docker compose --profile demo down -v && docker compose --profile demo up -d
```
Then sign in fresh. First saved-payee payment = **ALLOW**; acc-B's dep-001 is **ACTIVE** again.

---

## "What OTP do I put in the step-up box?" 😄

It's now **fully wired** — **enter any 6 digits** (e.g. `123456`) and hit **Confirm & send**.
The bank calls the decision-service lifecycle `POST /actions/{id}/confirm`, the held payment
**executes**, the **balance moves**, and the statement flips to **Sent**. (There's no real phone to
send a code to, so any 6 digits pass — the *friction* is the demonstration, not the code value.)

Likewise on a **Paused** (HOLD) payment, **Cancel payment** calls `POST /actions/{id}/cancel` for
real — the payment is cancelled and the statement shows **Cancelled**.

The full lifecycle (`confirm` / `cancel` / `approve` / `reject`) lives on the decision-service and
is driven through the gateway.
