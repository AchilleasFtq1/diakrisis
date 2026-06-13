# Diakrisis — Golden-Path Test Scenarios (SDD v4, §15)

**Status:** Authored from SDD v4 source of truth. Engine not yet built — these are
mechanisable specs, not runnable code. Each case maps to a POST /decision call
against the §11 API contract; assertions are against `engineVerdict` and
`combined.decision` only. `aiCoJudge` is explicitly excluded from assertions
(non-deterministic per §8.0).

**BA blockers captured below** as `BLOCKED:<owner>` — do not invent values for them.

---

## Signal weight reference (§6, Weights.java)

| Signal | Weight | Cap |
|--------|--------|-----|
| B1 New counterparty | 14 | — |
| B2 Counterparty age (decay) | 10 | — |
| B3 Added this session | 8 | — |
| B4 History depth | −12 | — |
| B5 Name mismatch (CoP) | 16 | — |
| P1 Alias re-point | 22 | — |
| A1 Amount anomaly | 12 | — |
| A2 Balance drain | 18 | — |
| A3 Counterparty-amount anomaly | 12 | — |
| A4 Threshold hugging | 6 | — |
| V1 Burst velocity | 8 | — |
| V2 Escalation | 10 | — |
| C1 Out-of-pattern time | 6 | — |
| C3 Retry pressure | 8 | — |
| G1 Unfamiliar geo | 12 | — |
| G2 New network | 6 | — |
| D1 Device age (decay) | 10 | — |
| D2 Platform anomaly | 6 | — |
| K1 Funds freed recently | 16 | — |
| K2 Limit raised recently | 10 | — |
| K3 Beneficiary-add burst | 8 | — |
| MP1 New-counterparty share | 16 | — |
| MP2 Cadence/total anomaly | 12 | — |
| MP4 Batch drain | 14 | — |
| M1 Model score | 18 | cap 18 |
| M2 Fraud-neighbor share | 12 | cap 12 |
| X1 Cross-account reputation | 20 | — |

## Band table (§8.1)

| Band | Score range | Default verdict | Instant/P2P rail shift |
|------|-------------|-----------------|------------------------|
| ALLOW | 0–29 | ALLOW | — |
| CONFIRM | 30–59 | CONFIRM | −8 (effective 22–51) |
| HOLD | 60–84 | HOLD | −8 (effective 52–76) |
| BLOCK | 85+ | BLOCK | — |

Non-monetary actions cap at CONFIRM regardless of score.

## Typology rule reference (§7)

| Ty# | Name | Rule (simplified) |
|-----|------|-------------------|
| Ty1 | Safe-account scam | B1 ∧ A2>0.7 ∧ (B3 ∨ D1>0.5 ∨ G1>0.5) |
| Ty2 | Invoice redirection | B5 ∧ A3 on established counterparty |
| Ty3 | Purchase scam | B1 ∧ rail∈{P2P,INSTANT} ∧ first-time modest amount |
| Ty4 | Romance / repeat victim | V2 across days ∧ B2>0.4 |
| Ty5 | Liquidation kill-chain | K1>0.6 ∧ B1 ∧ A2>0.6 |
| Ty6 | Payroll redirection | established batch pattern ∧ ≥1 line B5/changed-ref → quarantine line(s) |
| Ty7 | Mule fan-out | MP1>0.7 ∧ MP4>0.6 → batch HOLD/BLOCK |

Single typology match → outcome pinned to HOLD (floor and ceiling).
Two+ matches OR raw score ≥ 90 → BLOCK.

## Lifecycle state machine (§10, Fig 5)

```
DECIDED:
  ALLOW   → EXECUTED (immediate)
  CONFIRM → PENDING_CONFIRM → confirm → EXECUTED | abandon → ABANDONED
  REQUIRE_APPROVAL → PENDING_APPROVAL → approve (≠initiator) → EXECUTED
                                      → reject → REJECTED | 24h → EXPIRED
  HOLD    → HELD → cancel → CANCELLED
                 → release (only after expiry) → EXECUTED
                 → expiry untouched → LOCKED (NEVER auto-executes)
  BLOCK   → REVIEW
```

**LOCKED invariant:** a HOLD that expires without action becomes LOCKED.
LOCKED never auto-executes. Manual ops release required.
BLOCKED: exact LOCKED transition is undefined in SDD (BA 15:45 item 3) — see BLOCKED assertions below.

---

## ISO 20022 reason code reference (§8.4)

Codes documented in SDD: `FRAD` (fraudulent), `FOCR` (following cancellation/return),
`MS03` (reason not specified — never used), plus internal scheme tags:
`DKR-SAFEACCT`, `DKR-KILLCHAIN`, `DKR-XACCT`, `DKR-INVOICE`.

Full mapping table (every verdict/typology combination → exact reason code):
**BLOCKED: owner=BACKEND** (BA 14:22 item 5; BA 15:45 item — ISO 20022 mapping
table is absent from the spec; QA cannot assert correct codes for all combinations
until the table is published by BACKEND/PO).

Known mappings from §8.4 examples:

| Verdict | Typology | Reason code |
|---------|----------|-------------|
| HOLD/BLOCK | Ty5 (kill-chain) | `DKR-KILLCHAIN` |
| HOLD | Ty1 (safe-account) | `DKR-SAFEACCT` |
| HOLD | Ty2 (invoice redirect) | `DKR-INVOICE` |
| HOLD | X1 cross-account | `DKR-XACCT` |
| ALLOW | any | `MS03` (absent / silent) |

---

## T1 — Established bill payee (ALLOW + SCA-exempt)

**Demo scene:** Scene 1 — The Glide (0:25)

### Input
```json
{
  "event_id": "t1-001",
  "account_id": "acc-A",
  "event_type": "TRANSFER",
  "payload": {
    "counterparty": {
      "addressing": "IBAN",
      "value": "<known IBAN paid 41+ times>",
      "resolvedAccountRef": "<stable ref>",
      "resolvedName": "<matches display_name>"
    },
    "amountEur": 120.00,
    "availableBalanceEur": 4500.00,
    "rail": "SEPA"
  },
  "context": { "ts": "<now>", "sessionId": "s-t1", "channel": "WEB",
               "ip": "<known CIDR>", "device": { "deviceId": "<known>", "platform": "WEB" } }
}
```

### Expected signals
- B4 (History depth) fires negative: deep history, credited trust. B4 = −12.
- B1 must NOT fire (established counterparty).
- A1, A2, A3 near zero (normal amount, normal balance).

### Expected outcome
| Field | Expected value |
|-------|---------------|
| `engineVerdict.decision` | `ALLOW` |
| `engineVerdict.score` | 0–29 |
| `engineVerdict.scaExempt` | `true` |
| `engineVerdict.typologies` | `[]` (empty) |
| `combined.decision` | `ALLOW` |
| `explanation.customer` | `null` (silent — ALLOW is the product working silently) |
| `lifecycle` | `{ "executed": true }` or equivalent EXECUTED state |

### PASS/FAIL assertions
1. `engineVerdict.decision == "ALLOW"` — FAIL if CONFIRM, HOLD, or BLOCK
2. `engineVerdict.scaExempt == true` — FAIL if false (PSD2 RTS Art.18 TRA basis must be logged)
3. `combined.decision == "ALLOW"` — FAIL if diverges from engine
4. `explanation.customer == null` — FAIL if non-null (customer must not see friction for a clean transaction)
5. Score in [0, 29] — FAIL if ≥ 30
6. `engineVerdict.typologies` is empty — FAIL if any typology fires
7. Latency < 50 ms — FAIL if `latencyMs >= 50`

### ISO 20022 reason code
BLOCKED: full mapping table absent. Known: ALLOW decisions carry no fraud reason code. Assert `reasonCode` is absent or equals `MS03`.

---

## T2 — Standing-order rent payee (ALLOW + SCA-exempt)

**Demo scene:** Scene 1 — The Glide (0:25)

### Input
```json
{
  "event_id": "t2-001",
  "account_id": "acc-A",
  "event_type": "TRANSFER",
  "payload": {
    "counterparty": {
      "addressing": "IBAN",
      "value": "<rent IBAN, paid monthly, rhythmic>",
      "resolvedAccountRef": "<stable ref>",
      "resolvedName": "<matches>"
    },
    "amountEur": 750.00,
    "availableBalanceEur": 3200.00,
    "rail": "SEPA"
  },
  "context": { "ts": "<now>", "sessionId": "s-t2", "channel": "WEB",
               "ip": "<known CIDR>", "device": { "deviceId": "<known>", "platform": "WEB" } }
}
```

### Expected signals
- B4 fires (deep history), B2 near zero (old counterparty, fully decayed).
- MP2 (Cadence/total anomaly) near zero (rhythmic, expected day-of-month).

### Expected outcome
| Field | Expected value |
|-------|---------------|
| `engineVerdict.decision` | `ALLOW` |
| `engineVerdict.score` | 0–29 |
| `engineVerdict.scaExempt` | `true` |
| `combined.decision` | `ALLOW` |
| `explanation.customer` | `null` |

### PASS/FAIL assertions
1. `engineVerdict.decision == "ALLOW"` — FAIL if any friction outcome
2. `engineVerdict.scaExempt == true`
3. `combined.decision == "ALLOW"`
4. `explanation.customer == null`
5. Score in [0, 29]

---

## T3 — Anomalous amount to known payee (CONFIRM)

**Demo scene:** Scene 1 — The Glide (0:25) / general baseline

### Input
```json
{
  "event_id": "t3-001",
  "account_id": "acc-A",
  "event_type": "TRANSFER",
  "payload": {
    "counterparty": {
      "addressing": "IBAN",
      "value": "<known IBAN, avg payment ~120 EUR>",
      "resolvedAccountRef": "<stable ref>",
      "resolvedName": "<matches>"
    },
    "amountEur": 700.00,
    "availableBalanceEur": 3200.00,
    "rail": "SEPA"
  },
  "context": { "ts": "<now>", "sessionId": "s-t3", "channel": "WEB",
               "ip": "<known CIDR>", "device": { "deviceId": "<known>", "platform": "WEB" } }
}
```

### Expected signals
- A3 (Counterparty-amount anomaly, weight 12) fires: 700 >> 120 mean.
- B4 fires (known payee, negative weight). Net score: 30–59 band.

### Expected outcome
| Field | Expected value |
|-------|---------------|
| `engineVerdict.decision` | `CONFIRM` |
| `engineVerdict.score` | 30–59 |
| `engineVerdict.scaExempt` | `false` |
| `combined.decision` | `CONFIRM` |
| `explanation.customer` | non-null (one specific sentence) |

### PASS/FAIL assertions
1. `engineVerdict.decision == "CONFIRM"`
2. Score in [30, 59]
3. `A3` present in `engineVerdict.signals` with value > 0
4. `combined.decision == "CONFIRM"`
5. `explanation.customer != null`

---

## T4 — Invoice redirection: known supplier, changed ref (HOLD)

**Demo scene:** General golden path — Ty2 coverage

### Input
```json
{
  "event_id": "t4-001",
  "account_id": "acc-A",
  "event_type": "TRANSFER",
  "payload": {
    "counterparty": {
      "addressing": "IBAN",
      "value": "<IBAN different from established supplier IBAN>",
      "resolvedAccountRef": "<new ref>",
      "resolvedName": "Acme Supplies Ltd",
      "displayName": "Acme Supplies Ltd"
    },
    "amountEur": 4200.00,
    "availableBalanceEur": 12000.00,
    "rail": "SEPA"
  },
  "context": { "ts": "<now>", "sessionId": "s-t4", "channel": "WEB",
               "ip": "<known CIDR>", "device": { "deviceId": "<known>", "platform": "WEB" } }
}
```

### Context requirements
- Account has prior payment history to "Acme Supplies Ltd" under a different IBAN.
- `resolvedName` does NOT match the `displayName` expected by CoP → B5 fires (value 1.0, weight 16).
- Amount is anomalous vs counterparty mean → A3 fires.
- "Established counterparty" threshold: **BLOCKED: owner=BACKEND/BA** (BA 15:45 item 2 — numeric threshold for "established counterparty" in Ty2 is undefined; backend must specify min prior-payment count and time window before this test is fully mechanisable).

### Expected outcome
| Field | Expected value |
|-------|---------------|
| `engineVerdict.decision` | `HOLD` |
| `engineVerdict.typologies` | includes `"invoice_redirection"` (Ty2) |
| `combined.decision` | `HOLD` |

### PASS/FAIL assertions
1. `engineVerdict.decision == "HOLD"` — FAIL if CONFIRM or ALLOW
2. `engineVerdict.typologies` contains `"invoice_redirection"`
3. `combined.decision == "HOLD"`
4. `engineVerdict.signals` includes B5 with value 1.0
5. `reasonCode` matches `DKR-INVOICE` (BLOCKED: confirm against full mapping table)

### BLOCKED
- Ty2 "established counterparty" numeric threshold (BA 15:45 item 2): test cannot be seeded without knowing the exact prior-payment count required.

---

## T5a — Term deposit break (CONFIRM + purpose prompt)

**Demo scene:** Scene 2 — The Kill Chain (1:05) — first step of kill-chain

### Input
```json
{
  "event_id": "t5a-001",
  "account_id": "acc-B",
  "event_type": "TERM_DEPOSIT_BREAK",
  "payload": {
    "depositId": "dep-001",
    "principalEur": 5000.00,
    "maturityDate": "<future date>",
    "penaltyEur": 125.00
  },
  "context": { "ts": "<now>", "sessionId": "s-t5a", "channel": "WEB",
               "ip": "<any>", "device": { "deviceId": "<any>", "platform": "WEB" } }
}
```

### Critical BA acceptance criterion (BA 14:22 item 1)
TERM_DEPOSIT_BREAK is a customer's legal right under contract. The engine MUST always
emit CONFIRM+purpose. It MUST NEVER emit HOLD or BLOCK for this event type.
Non-monetary actions cap at CONFIRM (§8.1).

### Expected outcome
| Field | Expected value |
|-------|---------------|
| `engineVerdict.decision` | `CONFIRM` |
| `engineVerdict.score` | any (capped at CONFIRM by non-monetary rule) |
| `engineVerdict.typologies` | `[]` (no typology override to HOLD/BLOCK permitted) |
| `combined.decision` | `CONFIRM` |
| `explanation.customer` | non-null AND contains purpose-prompt text |

### PASS/FAIL assertions
1. `engineVerdict.decision == "CONFIRM"` — FAIL if HOLD or BLOCK (legal violation per BA 14:22)
2. `combined.decision == "CONFIRM"` — FAIL if HOLD or BLOCK
3. `explanation.customer != null` — FAIL if null (purpose prompt is mandatory)
4. `explanation.customer` contains a purpose question (e.g. "are you doing this because someone asked you to move money?")
5. `engineVerdict.typologies` does NOT contain any HOLD-pinning typology

### Notes
- This is a NEVER-FAIL invariant regardless of kill-chain posture, geo, or device context.
- Posture recording: this event MUST update `AccountPosture.fundsFreed72h` so K1 can fire on T5b.
- K1 fires on T5b, not T5a.

---

## T5b — Kill-chain drain transfer (HOLD — Ty5 liquidation kill-chain)

**Demo scene:** Scene 2 — The Kill Chain (1:05) — the drain step; posture chip must light up

### Input
```json
{
  "event_id": "t5b-001",
  "account_id": "acc-B",
  "event_type": "TRANSFER",
  "payload": {
    "counterparty": {
      "addressing": "IBAN",
      "value": "<new IBAN, added ~4 minutes ago>",
      "resolvedAccountRef": "<new ref, no prior history>",
      "resolvedName": "<any>",
      "beneficiaryCreatedAt": "<ts - 4 minutes>"
    },
    "amountEur": 4850.00,
    "availableBalanceEur": 4980.00,
    "rail": "SEPA"
  },
  "context": {
    "ts": "<ts of T5a + 20 minutes>",
    "sessionId": "s-t5b",
    "channel": "WEB",
    "ip": "<any>",
    "device": { "deviceId": "<any>", "platform": "WEB" }
  }
}
```

### Context requirements
- T5a must have been processed first so `fundsFreed72h` covers this amount → K1 fires.
- Beneficiary was added within the current session or moments ago → B1, B3 fire.
- Amount ≈ available balance (4850/4980 ≈ 0.97) → A2 fires (>0.8 threshold).
- Account posture chip in UI must show deposit-break event.

### Expected signals firing
- K1 (Funds freed recently, weight 16): `fundsFreed72h` covers 4850 EUR
- B1 (New counterparty, weight 14): no history on resolved identity
- B2 (Counterparty age decay, weight 10): payee created minutes ago ≈ 1.0
- B3 (Added this session, weight 8): beneficiary added in this session
- A2 (Balance drain, weight 18): 4850/4980 > 0.8

### Expected outcome
| Field | Expected value |
|-------|---------------|
| `engineVerdict.decision` | `HOLD` |
| `engineVerdict.typologies` | includes `"liquidation_kill_chain"` (Ty5) |
| `engineVerdict.score` | 60–84 (or pinned to HOLD by Ty5) |
| `combined.decision` | `HOLD` |
| `explanation.customer` | non-null, names the sequence/pattern |
| `lifecycle.hold.duration_minutes` | 30 (default) |
| `lifecycle.cancel_endpoint` | `/actions/t5b-001/cancel` |
| `lifecycle.release_endpoint` | `/actions/t5b-001/release` |

### PASS/FAIL assertions
1. `engineVerdict.decision == "HOLD"`
2. `engineVerdict.typologies` contains `"liquidation_kill_chain"`
3. `combined.decision == "HOLD"`
4. `engineVerdict.signals` contains K1 with value > 0.6
5. `engineVerdict.signals` contains A2 with value > 0.6
6. `explanation.customer != null`
7. `lifecycle.hold` present with `cancel_endpoint` and `release_endpoint`
8. `reasonCode` matches `DKR-KILLCHAIN` (BLOCKED: confirm against full mapping table)

---

## T6 — Romance / escalating pattern (HOLD — Ty4)

**Demo scene:** General golden path — Ty4 coverage

### Input
```json
{
  "event_id": "t6-001",
  "account_id": "acc-C",
  "event_type": "TRANSFER",
  "payload": {
    "counterparty": {
      "addressing": "IBAN",
      "value": "<counterparty receiving escalating amounts over 10+ days>",
      "resolvedAccountRef": "<ref>",
      "resolvedName": "<matches>"
    },
    "amountEur": 2000.00,
    "availableBalanceEur": 8000.00,
    "rail": "SEPA"
  },
  "context": { "ts": "<now>", "sessionId": "s-t6", "channel": "WEB",
               "ip": "<known CIDR>", "device": { "deviceId": "<known>", "platform": "WEB" } }
}
```

### Context requirements
- Prior payments to same counterparty over multiple days with rising amounts → V2 fires.
- Counterparty age ~10 days → B2 > 0.4 (B2 decays: ≈1.0 at minutes, ≈0.9 next day, →0 over ~90d).

### Expected signals
- V2 (Escalation, weight 10): rising amounts, same counterparty, across days
- B2 (Counterparty age, weight 10): value > 0.4

### Expected outcome
| Field | Expected value |
|-------|---------------|
| `engineVerdict.decision` | `HOLD` |
| `engineVerdict.typologies` | includes `"romance_repeat_victim"` (Ty4) |
| `combined.decision` | `HOLD` |

### PASS/FAIL assertions
1. `engineVerdict.decision == "HOLD"`
2. `engineVerdict.typologies` contains `"romance_repeat_victim"`
3. `combined.decision == "HOLD"`
4. V2 present in signals with value > 0
5. B2 present in signals with value > 0.4

---

## T7 — First P2P to MSISDN (CONFIRM + CoP)

**Demo scene:** General golden path — P2P / alias baseline

### Input
```json
{
  "event_id": "t7-001",
  "account_id": "acc-D",
  "event_type": "P2P_TRANSFER",
  "payload": {
    "counterparty": {
      "addressing": "MSISDN",
      "value": "+35799123456",
      "resolvedAccountRef": null,
      "resolvedName": "George Papadopoulos",
      "displayName": "George Papadopoulos"
    },
    "amountEur": 50.00,
    "availableBalanceEur": 1200.00,
    "rail": "P2P"
  },
  "context": { "ts": "<now>", "sessionId": "s-t7", "channel": "MOBILE_APP",
               "ip": "<known CIDR>", "device": { "deviceId": "<known>", "platform": "IOS" } }
}
```

### Context requirements
- No prior history on this MSISDN / resolved identity → B1 fires.
- P2P rail: rail shift −8 applies; a raw SEPA CONFIRM (score 30–59) on INSTANT/P2P
  becomes a HOLD (effective band edges shift −8, so CONFIRM band is 22–51).

### Expected signals
- B1 (New counterparty, weight 14): no history on resolved identity
- Rail shift applies: −8 to effective band thresholds

### Expected outcome
| Field | Expected value |
|-------|---------------|
| `engineVerdict.decision` | `CONFIRM` |
| `engineVerdict.score` | 30–59 range (raw SEPA band) |
| `combined.decision` | `CONFIRM` |
| `explanation.customer` | non-null, includes CoP (Confirmation of Payee) prompt |

### PASS/FAIL assertions
1. `engineVerdict.decision == "CONFIRM"` — FAIL if HOLD (P1 not firing, this is a first-time send, not a re-point)
2. `combined.decision == "CONFIRM"`
3. B1 fires in `engineVerdict.signals`
4. `explanation.customer` contains CoP prompt (resolvedName displayed for customer confirmation)
5. P1 MUST NOT fire (this is a first P2P, not an alias re-point)

---

## T8 — Alias re-points to new account (HOLD — any amount)

**Demo scene:** General golden path — SIM-swap / alias hijack

### Input
```json
{
  "event_id": "t8-001",
  "account_id": "acc-D",
  "event_type": "P2P_TRANSFER",
  "payload": {
    "counterparty": {
      "addressing": "MSISDN",
      "value": "+35799123456",
      "resolvedAccountRef": "<NEW account ref — different from historical resolution>",
      "resolvedName": "Unknown Person",
      "displayName": "George Papadopoulos"
    },
    "amountEur": 10.00,
    "availableBalanceEur": 1200.00,
    "rail": "P2P"
  },
  "context": { "ts": "<now>", "sessionId": "s-t8", "channel": "MOBILE_APP",
               "ip": "<known CIDR>", "device": { "deviceId": "<known>", "platform": "IOS" } }
}
```

### Context requirements
- The MSISDN "+35799123456" previously resolved to account X; now resolves to account Y.
- ObservationStore aliasKey history shows the re-point → P1 fires.
- Amount is deliberately tiny (10 EUR) to assert "fires on ANY amount."

### Expected signals
- P1 (Alias re-point, weight 22): alias resolves differently than its own history — SIM-swap tell

### Expected outcome
| Field | Expected value |
|-------|---------------|
| `engineVerdict.decision` | `HOLD` |
| `engineVerdict.score` | 60+ (P1 alone = 22; with B1/B3 likely >60) |
| `combined.decision` | `HOLD` |

### PASS/FAIL assertions
1. `engineVerdict.decision == "HOLD"` — FAIL if CONFIRM or ALLOW (P1 is identity signal, not monetary; fires on any amount)
2. P1 present in `engineVerdict.signals` with value 1.0
3. `combined.decision == "HOLD"`
4. `explanation.customer != null`

### Notes
- The amount (10 EUR) is deliberately trivial. If the engine returns CONFIRM or ALLOW here,
  the SIM-swap / alias hijack detection is broken, regardless of amount.

---

## T9 — Salami slicing: third slice crosses logical-amount threshold (HOLD)

**Demo scene:** General golden path — salami closure

### Input sequence
Three transfers within a 2-hour window to the same resolved counterparty:

**Slice 1** (event_id: t9-s1): 850 EUR, ~30 minutes before test time
**Slice 2** (event_id: t9-s2): 850 EUR, ~15 minutes before test time
**Slice 3** (event_id: t9-s3, the asserted call):
```json
{
  "event_id": "t9-s3",
  "account_id": "acc-E",
  "event_type": "TRANSFER",
  "payload": {
    "counterparty": {
      "addressing": "IBAN",
      "value": "<same IBAN as slices 1+2>",
      "resolvedAccountRef": "<same resolved ref>",
      "resolvedName": "<matches>"
    },
    "amountEur": 850.00,
    "availableBalanceEur": 5000.00,
    "rail": "SEPA"
  },
  "context": { "ts": "<now>", "sessionId": "s-t9", "channel": "WEB",
               "ip": "<known CIDR>", "device": { "deviceId": "<known>", "platform": "WEB" } }
}
```

### Logical-amount rule (§6)
A1 and A3 evaluate `max(thisAmount, Σ_24h_same_resolved_counterparty)`.
On slice 3: logical amount = max(850, 850+850+850) = 2550 EUR.
A2 (balance drain): logical_amount / available = 2550/5000 = 0.51 (>0 but watch threshold).
A3: 2550 vs counterparty mean → anomalous.

### BLOCKED assertions (BA 15:45 items 4, 5)
- **BLOCKED:BACKEND** — Exact 24h accumulation reset boundary: rolling 24h from the time of
  the first slice, or from each individual slice's timestamp? Backend must specify before
  this test can assert the exact Σ_24h value.
- **BLOCKED:BACKEND** — AI_ESCALATION_THRESHOLD is hard-coded at 80 in Weights.java (BA 15:45
  item 5) but is not surfaced as a named constant in the SDD table — backend must confirm
  the constant name and value so this test and T14 reference it consistently.

### Expected outcome
| Field | Expected value |
|-------|---------------|
| `engineVerdict.decision` | `HOLD` |
| `engineVerdict.score` | 60+ |
| `combined.decision` | `HOLD` |

### PASS/FAIL assertions
1. `engineVerdict.decision == "HOLD"` on slice 3 — FAIL if CONFIRM or ALLOW
2. A1 or A3 in `engineVerdict.signals` with value derived from logical amount (> individual slice amount)
3. `combined.decision == "HOLD"`
4. Slices 1 and 2 may independently return CONFIRM or ALLOW (below HOLD band before accumulation crosses threshold)

---

## T10 — Stacked signals: new device + new payee + drain + retries + foreign IP (BLOCK)

**Demo scene:** General golden path — stacked / maximum-friction case

### Input
```json
{
  "event_id": "t10-001",
  "account_id": "acc-F",
  "event_type": "TRANSFER",
  "payload": {
    "counterparty": {
      "addressing": "IBAN",
      "value": "<IBAN never seen before>",
      "resolvedAccountRef": "<brand new ref>",
      "resolvedName": "<mismatch>",
      "displayName": "<original name>"
    },
    "amountEur": 9500.00,
    "availableBalanceEur": 10000.00,
    "rail": "SEPA"
  },
  "context": {
    "ts": "<now>",
    "sessionId": "s-t10",
    "channel": "WEB",
    "ip": "<foreign IP, country ≠ account home>",
    "device": { "deviceId": "<never seen before>", "platform": "WEB" }
  }
}
```

### Context requirements
- Retry pressure: C3 fires (raised-amount attempts in session).
- New device → D1 fires (high value ≈1.0, first sighting).
- Foreign IP → G1 fires.
- B1 (new counterparty), A2 (drain: 9500/10000 > 0.8), B5 (name mismatch).

### Expected signals
B1 (14) + A2 (18) + G1 (12) + D1 (10) + C3 (8) = 62 base.
With B5 (16) firing: 78+. Typology Ty1 likely fires (B1 ∧ A2>0.7 ∧ D1>0.5 ∧ G1>0.5).
Score ≥ 85 → BLOCK (or two typologies → BLOCK).

### Expected outcome
| Field | Expected value |
|-------|---------------|
| `engineVerdict.decision` | `BLOCK` |
| `engineVerdict.score` | 85+ |
| `combined.decision` | `BLOCK` |
| `lifecycle` | REVIEW state |

### PASS/FAIL assertions
1. `engineVerdict.decision == "BLOCK"`
2. Score ≥ 85
3. `combined.decision == "BLOCK"`
4. `lifecycle` indicates REVIEW

---

## T11 — Payroll batch: 50 lines, 1 redirected (REQUIRE_APPROVAL + L02 quarantined)

**Demo scene:** Scene 3 — The Breadth + Moat (0:40)

### Input
```json
{
  "event_id": "e-5801",
  "account_id": "biz-0042",
  "event_type": "MASS_PAYMENT",
  "payload": {
    "batch_id": "PAYROLL-2026-06",
    "purpose_hint": "PAYROLL",
    "items": [
      { "item_id": "L01", "counterparty": { "addressing": "IBAN",
        "value": "CY11-...-771", "resolvedName": "E. CHARALAMBOUS" }, "amount_eur": 1420.00 },
      { "item_id": "L02", "counterparty": { "addressing": "IBAN",
        "value": "LT44-...-902", "display_name": "M. Ioannou",
        "resolvedName": "M. IOANNOU" }, "amount_eur": 1380.00 },
      "... 48 more lines ..."
    ],
    "total_eur": 68400.00,
    "available_balance_eur": 91200.00,
    "rail": "SEPA"
  },
  "context": { "...ctx": "..." }
}
```

### Context requirements
- Account biz-0042 has ≥3 months prior payroll batch history (established batch pattern for Ty6).
- L02's IBAN changed from prior batch (B5 fires on L02 line).
- 49 lines are clean (same IBAN as prior batch history).
- MASS_PAYMENT on business account → REQUIRE_APPROVAL always fires (§8.2 Policy: corporate dual-auth).

### Expected outcome
| Field | Expected value |
|-------|---------------|
| `engineVerdict.decision` | `REQUIRE_APPROVAL` |
| `engineVerdict.score` | any (corporate dual-auth overrides regardless) |
| `engineVerdict.typologies` | includes `"payroll_redirection"` (Ty6) with lines `["L02"]` |
| `combined.decision` | `REQUIRE_APPROVAL` |
| `items[L01].decision` | `ALLOW` |
| `items[L02].decision` | `HOLD` (quarantined) |
| `items[L02].signals` | includes B5 with value 1.0 |
| `items[L03..L50].decision` | `ALLOW` (48 clean lines) |
| `approval.reason` | `"POLICY_CORPORATE_BATCH"` |
| `approval.approve_endpoint` | `/actions/e-5801/approve` |
| `approval.reject_endpoint` | `/actions/e-5801/reject` |
| `approval.expires_in_hours` | 24 |
| `explanation.customer` | non-null: names the quarantined line |

### PASS/FAIL assertions
1. `engineVerdict.decision == "REQUIRE_APPROVAL"`
2. `combined.decision == "REQUIRE_APPROVAL"`
3. Exactly 1 item with `decision == "HOLD"` and `item_id == "L02"`
4. Exactly 49 items with `decision == "ALLOW"`
5. L02 signals include B5 value 1.0
6. `typologies` contains `"payroll_redirection"`
7. `approval.reason == "POLICY_CORPORATE_BATCH"`
8. `approval.expires_in_hours == 24`
9. `explanation.customer != null`

### Post-approval invariant (§11.13 + §8.2)
After `POST /actions/e-5801/approve` with approver ≠ initiator:
- Response `items_executed: 49`, `items_held: ["L02"]`
- L02 remains HELD; 49 lines execute.

### REQUIRE_APPROVAL no-approver-designated fallback
**BLOCKED:BACKEND** (BA 15:45 item 1): SDD §8.2 states "risk-routed HOLD on accounts
WITH a designated approver". What happens when score is 60–84 and no approver is
designated? Backend must define and implement the fallback (expected: HOLD stays HOLD,
not silently dropped). QA will add a T11b case once confirmed.

---

## T12 — 30-line mule fan-out batch (BLOCK)

**Demo scene:** General golden path — Ty7 mule fan-out

### Input
```json
{
  "event_id": "t12-001",
  "account_id": "biz-9999",
  "event_type": "MASS_PAYMENT",
  "payload": {
    "batch_id": "BATCH-FANOUT-01",
    "purpose_hint": "OTHER",
    "items": "<30 lines, all new counterparties never seen before>",
    "total_eur": 85000.00,
    "available_balance_eur": 87000.00,
    "rail": "SEPA"
  },
  "context": { "...ctx": "..." }
}
```

### Context requirements
- MP1 (New-counterparty share, weight 16): ≈30/30 new counterparties → MP1 ≈ 1.0 (>>0.7 threshold).
- MP4 (Batch drain, weight 14): totalEur/availableBalance = 85000/87000 ≈ 0.98 (>>0.6 threshold).
- Ty7 fires: MP1>0.7 ∧ MP4>0.6 → batch HOLD/BLOCK.
- BLOCK because Ty7 fires and score ≥ 85, or two typologies fire.

### Expected outcome
| Field | Expected value |
|-------|---------------|
| `engineVerdict.decision` | `BLOCK` |
| `engineVerdict.typologies` | includes `"mule_fan_out"` (Ty7) |
| `combined.decision` | `BLOCK` |

### PASS/FAIL assertions
1. `engineVerdict.decision == "BLOCK"`
2. `combined.decision == "BLOCK"`
3. `engineVerdict.typologies` contains `"mule_fan_out"`
4. MP1 in signals with value > 0.7
5. MP4 in signals with value > 0.6

---

## T13 — Self-approval attempt (403)

**Demo scene:** General golden path — four-eyes control invariant

### Input
`POST /actions/e-5801/approve` with `X-User: <same user who initiated e-5801>`

### Context requirements
- Event e-5801 is in PENDING_APPROVAL state (use the T11 setup).
- The approving user is the same as the initiating user.

### Expected outcome
| Field | Expected value |
|-------|---------------|
| HTTP status | 403 |
| Response body | `{ "error": "SELF_APPROVAL_FORBIDDEN" }` |

### PASS/FAIL assertions
1. HTTP 403 returned — FAIL if 200 (would allow coercion of single victim)
2. Response body contains `SELF_APPROVAL_FORBIDDEN`
3. Action remains in PENDING_APPROVAL state (not executed, not rejected)

### Notes
- This is a structural coercion-resistance invariant. A coached victim cannot approve
  their own transfer. Any 2xx response here is a critical security defect.

---

## T14 — Co-judge divergence escalation at AI_ESCALATION_THRESHOLD (combined = HOLD)

**Demo scene:** §12 demo scene reference; §8.3 rule pin

### Setup
Engine produces CONFIRM at score 52. AI co-judge returns DIVERGE_STRICTER at score 88.

### AI_ESCALATION_THRESHOLD
Per §8.3 and Weights.java: `AI_ESCALATION_THRESHOLD = 80`.
**BLOCKED:BACKEND** (BA 15:45 item 5): this constant must be surfaced as a named,
testable constant in Weights.java. If backend changes the value, the test must break
explicitly, not silently. Assert the constant value is exactly 80 at test time.

### Expected §8.3 rule application
| aiVerdict | Engine score | Condition | Action on combined |
|-----------|-------------|-----------|-------------------|
| DIVERGE_STRICTER | 88 | score ≥ AI_ESCALATION_THRESHOLD (80) | escalate exactly one band: CONFIRM → HOLD |

### Expected outcome
| Field | Expected value |
|-------|---------------|
| `engineVerdict.decision` | `CONFIRM` |
| `engineVerdict.score` | 52 |
| `aiCoJudge.agreement` | `DIVERGE_STRICTER` |
| `aiCoJudge.score` | 88 |
| `combined.decision` | `HOLD` (one-band escalation, CONFIRM → HOLD) |
| `combined.basis` | contains "engine CONFIRM escalated to HOLD: AI co-judge DIVERGE_STRICTER @88" |

### PASS/FAIL assertions
1. `engineVerdict.decision == "CONFIRM"` — engine is authoritative, unchanged
2. `combined.decision == "HOLD"` — FAIL if CONFIRM (escalation did not fire) or BLOCK (over-escalated)
3. `combined.basis` contains the escalation description
4. Combined is capped at HOLD — FAIL if BLOCK (AI can never force BLOCK per §8.3)
5. `aiCoJudge` assertions are ADVISORY only — do not assert aiCoJudge.decision or aiCoJudge.score for PASS/FAIL (non-deterministic)

### Diverge-softer invariant (§8.3 — separate assertion)
If engine returns CONFIRM and aiCoJudge returns DIVERGE_SOFTER:
- `combined.decision` MUST equal `engineVerdict.decision` (CONFIRM)
- The AI can never talk the engine out of caution.

### Low-confidence stricter invariant (§8.3 — separate assertion)
If aiCoJudge returns DIVERGE_STRICTER but score < 80:
- `combined.decision` MUST equal `engineVerdict.decision` (no escalation)
- Only a `review_flag` on ops feed.

---

## T15 — Cross-account counterparty reputation: X1 fires on account B after flag on account A

**Demo scene:** Scene 3 — The Breadth + Moat (0:40) — "flagged 30 seconds ago" moment

### Setup
1. Account A submits a transfer to counterparty CP-MULE. Engine returns HOLD (or BLOCK).
   This writes CP-MULE to `CounterpartyReputation` store with `lastFlagAt = now`, `worstOutcome = HOLD`.
2. Account B (different account, same or different user) submits a transfer to CP-MULE.

### Input for step 2 (account B's transfer)
```json
{
  "event_id": "t15-b-001",
  "account_id": "acc-B2",
  "event_type": "TRANSFER",
  "payload": {
    "counterparty": {
      "addressing": "IBAN",
      "value": "<CP-MULE IBAN>",
      "resolvedAccountRef": "<CP-MULE resolved ref>",
      "resolvedName": "<CP-MULE name>"
    },
    "amountEur": 200.00,
    "availableBalanceEur": 5000.00,
    "rail": "SEPA"
  },
  "context": {
    "ts": "<now + 30 seconds after account A's HOLD>",
    "sessionId": "s-t15-b",
    "channel": "WEB",
    "ip": "<known CIDR for acc-B2>",
    "device": { "deviceId": "<known for acc-B2>", "platform": "WEB" }
  }
}
```

### Context requirements
- CP-MULE's resolved counterparty identity was flagged (HELD or BLOCKED) on account A within
  the X1 decay window (hours, decaying per §4B).
- Account B's transfer is otherwise unremarkable (small amount, known device/IP, no other signals).
- X1 (Cross-account reputation, weight 20) fires on account B's decision.

### X1 decay window
**BLOCKED:BACKEND** (BA 15:45 item 6): §4B states X1 decays "over hours" but the exact
half-life or decay formula is unquantified. Backend must specify the exact decay window
so QA can assert: (a) X1 fires within window; (b) X1 does NOT fire after window expires.
Two sub-tests needed once the window is defined:
- T15-FIRE: submit account B's transfer within the decay window → X1 fires → HOLD
- T15-NOFIR: submit after window expires → X1 = 0 → outcome driven by other signals only

### Expected outcome (within decay window)
| Field | Expected value |
|-------|---------------|
| `engineVerdict.decision` | `HOLD` |
| `engineVerdict.signals` | includes X1 with value > 0 |
| `combined.decision` | `HOLD` |
| `explanation.customer` | non-null, contains "flagged [N] seconds ago in another scam attempt" warning |

### PASS/FAIL assertions (T15-FIRE, within window)
1. `engineVerdict.decision == "HOLD"` — FAIL if ALLOW or CONFIRM (X1 not firing)
2. X1 present in `engineVerdict.signals` with value > 0
3. `combined.decision == "HOLD"`
4. `explanation.customer` or `explanation.audit` contains the cross-account warning text
5. `reasonCode` matches `DKR-XACCT` (BLOCKED: confirm against full mapping table)

### PASS/FAIL assertions (T15-NOFIR, after decay window — BLOCKED until window defined)
- BLOCKED:BACKEND — window value required before this assertion can be written.

---

## Contract invariants (§11 — apply across all tests)

These apply to every POST /decision call regardless of scenario.

| # | Invariant | Assertion |
|---|-----------|-----------|
| CI-1 | Idempotent replay | Re-submitting the same event_id returns the stored decision (no re-scoring); HTTP 200 with identical body |
| CI-2 | LOCKED never auto-executes | A HOLD that reaches LOCKED state must NOT transition to EXECUTED without an explicit ops release call |
| CI-3 | Initiator ≠ approver | POST /actions/{id}/approve with X-User = initiator → 403 SELF_APPROVAL_FORBIDDEN |
| CI-4 | combined === engine (baseline) | combined.decision == engineVerdict.decision when aiCoJudge is CONCUR or absent |
| CI-5 | combined at most one-band escalation | combined.decision never skips two bands; never equals BLOCK when engine is CONFIRM |
| CI-6 | combined never softer than engine | If aiCoJudge is DIVERGE_SOFTER, combined.decision == engineVerdict.decision |
| CI-7 | explanation.customer null iff silent | explanation.customer is null for ALLOW; non-null for CONFIRM, HOLD, BLOCK, REQUIRE_APPROVAL |
| CI-8 | Malformed input → 400/422, never 500 | Missing required fields, garbage types, extra unknown fields → 400 or 422, never 500 |
| CI-9 | Non-monetary actions cap at CONFIRM | TERM_DEPOSIT_BREAK, BENEFICIARY_ADD, LIMIT_CHANGE score may exceed CONFIRM band but outcome is capped |
| CI-10 | HOLD release only after expiry | POST /actions/{id}/release before hold expiry → rejected (LOCKED semantics); **BLOCKED:BACKEND** — exact error code/state for pre-expiry release attempt undefined in SDD |
| CI-11 | Latency < 50 ms | latencyMs < 50 on every POST /decision response |

---

## BLOCKED assertions summary

| # | Item | Owner | BA ref |
|---|------|-------|--------|
| BL-1 | TERM_DEPOSIT_BREAK always CONFIRM+purpose — acceptance criterion in engine code | BACKEND | BA 14:22 item 1 |
| BL-2 | §8.2 no-approver-designated fallback at score 60–84: fallback behavior (HOLD stays HOLD?) | BACKEND | BA 15:45 item 1 |
| BL-3 | Ty2 "established counterparty" numeric threshold (min prior-payment count + time window) | BACKEND/BA | BA 15:45 item 2 |
| BL-4 | LOCKED state subsequent transition: manual ops release or terminal? What error on pre-expiry release? | BACKEND | BA 15:45 item 3 |
| BL-5 | T9 salami 24h accumulation reset: rolling from first slice or each slice? | BACKEND | BA 15:45 item 4 |
| BL-6 | AI_ESCALATION_THRESHOLD = 80: must be surfaced as named constant in Weights.java | BACKEND | BA 15:45 item 5 |
| BL-7 | X1 decay window: exact half-life or decay formula for T15-FIRE / T15-NOFIR split | BACKEND | BA 15:45 item 6 |
| BL-8 | ISO 20022 full mapping table (every verdict/typology → reason code) | BACKEND/PO | BA 14:22 item 5 |

---

## Demo critical path coverage

| §12 scene | Time | Cases |
|-----------|------|-------|
| Scene 1: The Glide | 0:25 | T1, T2 (ALLOW+exempt); T3 (CONFIRM baseline) |
| Scene 2: The Kill Chain | 1:05 | T5a (deposit break CONFIRM+purpose); T5b (drain HOLD, Ty5, names sequence) |
| Scene 3: The Breadth + Moat | 0:40 | T11 (payroll REQUIRE_APPROVAL, L02 quarantined); T15 (X1 cross-account HOLD) |
| Scene 4: Who pays + close | 0:50 | (talk track — no golden-path test assigned) |

Cases NOT on the critical path (important but not scene-assigned): T4, T6, T7, T8, T9, T10, T12, T13, T14.

---

## Future harness notes (do not build until engine exists)

### API golden-path runner (JUnit 5 + MockMvc)
- One test method per T1–T15 case, seeding the in-memory RuntimeState and ObservationStore
  via a `@BeforeEach` fixture helper.
- Each test posts to `POST /decision` via MockMvc, then asserts the fields listed above.
- T11 additionally calls `POST /actions/{id}/approve` (second user header) and asserts
  the `items_executed / items_held` split.
- T13 asserts HTTP 403 from `POST /actions/{id}/approve` with initiator header.
- T14 seeds a mock AiCoJudge stub returning `DIVERGE_STRICTER @88` and asserts `combined`.
- T15 requires two sequential calls (account A HOLD, then account B within decay window);
  the CounterpartyReputation store must be shared in-process.
- Contract invariants CI-1 through CI-11 run as a separate parametrized test suite.

### Browser E2E (Playwright against localhost:5173 + :8080)
- Each §12 scene is one `test` block; per-session sandbox seeded via
  `POST /demo/session` before each run so sessions never collide.
- Scene 1 asserts: ALLOW decision visible, exemption counter increments, no friction rendered.
- Scene 2 asserts: CONFIRM decision with purpose prompt rendered; posture chip lights up after
  deposit break; after adding payee and sending 4850 EUR → HOLD rendered; typology name
  "Liquidation kill-chain" visible; cancel button present; saved-counter ticks on cancel.
- Scene 3 asserts: payroll batch → REQUIRE_APPROVAL rendered; approver inbox shows L02 held;
  second browser to CP-MULE shows "flagged 30 seconds ago" warning (X1); approve as second
  user → 49 executed, L02 stays held.
- Controls scene asserts: self-approval attempt shows 403 / SELF_APPROVAL_FORBIDDEN in UI.
- Assert on: visible decision badge, named typology text, posture chip state, ops-feed entry,
  latency print on ops dashboard, explanation.customer rendered for CONFIRM/HOLD, approver inbox count.
