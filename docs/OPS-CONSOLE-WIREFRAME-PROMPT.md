# Wireframe prompt — Diakrisis Fraud-Ops Console

Paste the prompt below into a design AI (Claude with the frontend-design skill, v0, Figma AI, etc.)
to generate wireframes for the platform's internal fraud-operations dashboard — the operational
counterpart to the customer banking app (which deliberately hides all engine detail). The data
model below matches the real `ops-service` DTOs (`FeedEntry`, `CountersView`, `ApprovalEntry`).

---

```
Design wireframes for the "Diakrisis Fraud-Ops Console" — the internal dashboard a bank's
fraud analysts and approvers use to watch a real-time scam-decision engine. This is the
operational counterpart to the customer banking app (which deliberately hides all engine
detail); the console is where ALL the engine internals are exposed.

CONTEXT
Diakrisis is a real-time risk-decision API a bank calls before executing any customer payment.
For each action it returns one of five graduated outcomes — ALLOW, CONFIRM, REQUIRE_APPROVAL,
HOLD, BLOCK — by combining ~27 explainable signals + named scam "typologies" (e.g.
liquidation_kill_chain, safe_account_scam, invoice_redirection, romance_escalation). Its
signature move is catching "kill-chains": multi-step authorised-push-payment scams (break a
deposit → add a payee → drain) where each step looks innocent but the SEQUENCE is the scam.
Decisions are made in <50 ms. This console is what an analyst stares at all day, and what we
demo to judges to PROVE the engine is working.

AUDIENCE / TONE
Professional fraud analysts. The aesthetic should read like a serious financial-crime operations
console: data-dense, calm, trustworthy, high-signal. Prefer a dark, technical "control room"
theme with one strong accent for risk (amber→red severity scale) and a clear typographic
hierarchy. Monospace for IDs/amounts/scores. NOT playful, NOT a generic SaaS dashboard.

SCREENS TO WIREFRAME
1. Login — analyst sign-in (username + password, role: OPS / APPROVER / ADMIN). Minimal.

2. Overview dashboard (the hero screen) — top row of KPI stat-cards, then a live decision feed.
   KPI cards (from real counters):
     • Total decisions
     • Breakdown by outcome (ALLOW / CONFIRM / REQUIRE_APPROVAL / HOLD / BLOCK) — a small
       horizontal bar or segmented stat
     • Confirmed saves (frauds stopped)  • False positives
     • Money protected (€)               • SCA-exemption rate (%)
     • p50 decision latency (ms) — with a "<50ms SLA" indicator
   Live decision feed (table, newest first, auto-refresh feel): time, account, initiator,
   payment type, amount, OUTCOME (color-coded pill), risk SCORE 0–100 (with a severity bar),
   named TYPOLOGIES (chips), reason code, lifecycle state (EXECUTED / PENDING_CONFIRM / HELD /
   PENDING_APPROVAL / REVIEW). A row expands to a decision detail.

3. Decision detail — one decision fully explained: the score and how it was reached (the
   contributing signals as a weighted bar list — signal id, value, weight, contribution),
   the typologies that fired, the customer-facing explanation that was shown, the verdict +
   combined (engine vs AI co-judge), latency, and — crucially — the KILL-CHAIN TIMELINE: the
   sequence of earlier actions on this account (e.g. "deposit broken 4 min ago → funds freed →
   this drain") that the engine remembered. Make the kill-chain visually obvious.

4. Approval queue (four-eyes) — actions in PENDING_APPROVAL: event id, initiator, amount,
   batch held line-items, hold-expiry countdown, with Approve / Reject actions (an approver or
   admin acts; the initiator can't approve their own — show that guard).

5. (optional) Account posture view — for one account: its risk posture, recent observations
   (devices/IPs/geo), funds-freed window, and decision history.

DATA MODEL (use these exact fields)
Feed entry: { eventId, accountId, initiatorSub, lifecycleState, createdAt, holdExpiresAt,
              + ideally: verdict, score(0-100), typologies[], reasonCode, amount, eventType }
Counters:   { total, byLifecycleState{state→count}, confirmedSaves, falsePositives,
              moneySavedCents, exemptionRate(0-1), p50LatencyMs }
Approval:   { eventId, state, initiatorUserId, holdExpiresAt, batchHeldItemIds[], createdAt }
Outcomes:   ALLOW, CONFIRM, REQUIRE_APPROVAL, HOLD, BLOCK
Lifecycle:  DECIDED, EXECUTED, PENDING_CONFIRM, HELD, PENDING_APPROVAL, REVIEW, CANCELLED,
            REJECTED, EXPIRED, ABANDONED, LOCKED
Typologies: liquidation_kill_chain, safe_account_scam, invoice_redirection, romance_escalation,
            salami_slicing, alias_repoint, mule_fan_out

COMPONENTS / PATTERNS TO INCLUDE
- A persistent left sidebar nav: Overview · Decisions · Approvals · Accounts · (sign-out + role badge)
- Outcome pills with a consistent color scale (ALLOW green → CONFIRM amber → HOLD orange →
  BLOCK red; REQUIRE_APPROVAL distinct).
- A 0–100 risk score shown as a number + a severity meter.
- Typology chips (compact, monospace).
- Empty states and a "live / paused" auto-refresh affordance.

DELIVERABLE
Low-to-mid fidelity wireframes for screens 1–4 (5 optional), desktop-first (analyst workstation),
with the Overview dashboard and the Decision detail (kill-chain timeline) as the most polished.
Annotate the color scale and the score/severity component.
```

---

## Notes for whoever implements it afterwards

- The real `ops-service` (`:8082`) already serves the data as JSON: `GET /ops/feed`,
  `/ops/counters`, `/ops/approvals` (OPS/APPROVER role). The feed currently returns lifecycle
  fields; to populate the score/typologies/verdict columns the wireframe shows, enrich `FeedEntry`
  (the data is on the `Decisions` table the feed already reads).
- The lifecycle actions (`approve`/`reject`/`confirm`/`cancel`) live on the **decision-service**
  (`/actions/{id}/...`); the Approvals screen's buttons call those (an APPROVER or ADMIN; the
  initiator can't self-approve — already enforced server-side).
- Auth: same iam JWT as the rest of the platform. If the console is served as its own session UI
  (like the demo-bank), have it log in via iam and hold the principal in session.
