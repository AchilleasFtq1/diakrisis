# Diakrisis — demo script & test values

Exactly what to type to make each outcome happen, plus the logins and the API equivalents.
**The engine is stateful** — for a clean demo run a reset first (see bottom).

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

### ⛔ BLOCK → "Declined" — the kill-chain (the money shot)
1. Sign in **customer-B** → **Account** → Term deposits → **Break dep-001**. (CONFIRM — funds freed.)
2. → **Payments** → **Pay someone new** → any IBAN, amount **4850.00**, SEPA.
- Engine: `BLOCK`, score **99**, typologies **`liquidation_kill_chain, safe_account_scam`**.
- The point: the engine **remembered the deposit break** seconds earlier and blocked the sweep.
  Customer sees *"We've paused this payment to protect you"* — no codes.

### ⏸ HOLD → "Paused" + named scam warning
- Live, a single new-payee payment trends CONFIRM→BLOCK as risk stacks; the **pure HOLD**
  scenarios (invoice-redirection / confirmation-of-payee mismatch, romance escalation, salami,
  alias re-point, cross-account moat) are pinned and verified in the golden-path test suite
  (T4, T6, T8–T10, T14). They produce `HOLD` with one typology and the named warning.

### 👥 REQUIRE_APPROVAL → "Awaiting approval"
- A **business mass-payment** on an account with a designated approver (four-eyes). Verified in the
  suite (T11/T13: a second APPROVER/ADMIN approves; the initiator can't self-approve → 403). The
  retail demo accounts have no approver, so use the API/test path for this one.

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
# ALLOW
curl -s -X POST $B/api/transfer -H 'Content-Type: application/json' \
     -d '{"account":"acc-A","payee":"CD|46939146","amount":120.00}'
# kill-chain → BLOCK
curl -s -X POST $B/api/deposits/dep-001/break -H 'Content-Type: application/json'
curl -s -X POST $B/api/transfer-new -H 'Content-Type: application/json' \
     -d '{"account":"acc-B","iban":"LU00DRAIN0001","resolvedName":"Z","amount":4850.00}'
```
(The `/api/*` responses include the full engine verdict — that's the analyst view, not the bank UI.)

---

## ⚠️ Reset between demos (the engine is stateful, on purpose)

Each payment is remembered (velocity, posture). After a few payments, a normal transfer may
escalate — that's the engine working, but it means the "first payment = ALLOW" trick needs a clean
slate. To reset (wipes in-memory DynamoDB and re-seeds Berka):
```bash
cd diakritis
docker compose --profile demo down && docker compose --profile demo up -d
```
Then sign in fresh. First saved-payee payment = **ALLOW**.

---

## "What OTP do I put in the step-up box?" 😄

The CONFIRM **SCA step-up box** is a **visual mock** — there is **no real OTP** wired, and the
"Confirm & send" button is intentionally non-interactive (it shows the *affordance* — the friction
a real bank would apply). So: any digits "work" visually; the button doesn't submit anything.

The real machinery exists — the decision-service has `POST /actions/{id}/confirm` (and
`/cancel`, `/approve`, `/reject`) that drive the lifecycle. They're just not wired to the demo
box yet. **If you want it functional** (type a code → actually execute the held payment, balance
moves), that's a small addition — ask and I'll wire the box to `/actions/{id}/confirm`.
