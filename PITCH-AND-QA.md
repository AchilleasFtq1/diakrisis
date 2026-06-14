# 🎤 Diakrisis — 3-min Pitch Script + 2-min Q&A Cheat-Sheet

*Built from a 4-judge mock panel (Elena/CTO 87 · Panis/innovation 87 · Nikiforos/AWS 83 · Andreas/academic 81 — avg ~84/100). The judges independently verified our claims, so the winning move is **radical honesty + the story-first demo**.*

## 3 non-negotiable rules
1. **Lead with the STORY and the live demo — not the architecture.** A non-technical room glazes over at "Java 26 / GradientTreeBoost." Make them *feel* the scam first.
2. **Say the honest caveats yourself, before a judge catches them.** Runs locally (AWS-ready), ML is card-data not APP-data, <50ms is steady-state not load-tested. Honesty *earns* points here; getting caught *loses* them.
3. **Say the spine line out loud:** *"any one — or all — of the ML can fail and the engine still decides, fully-explainable, in under 50ms, because the deterministic rules engine is the spine and the ML/AI are weight-capped advisors that can never override it."*

> ✅ **DOC LANDMINE CLEARED:** `MODEL_CARD.md` (and the README/feature-spec/AWS docs) now match the
> committed artifacts — **300 trees / unweighted; `val_scores.csv` reproduces PR-AUC 0.422, ROC-AUC
> 0.834, base rate 3.48%.** The academic judge *will* recompute it, and now it checks out. **Quote the
> artifact numbers, and invite them to recompute** — that honesty is a *strength* (see the ML Q&A below).

---

## ⏱️ THE 3-MINUTE PITCH (script)

**[0:00 — HOOK. No slides. Look at the judges.]**
> "Your mum gets a call: *'We've detected fraud — quickly, move your savings to a safe account.'* She breaks her term deposit and sends €5,000 to the 'safe' account. **It was the scam.** Every step looked normal to her bank, so it went through. And since **October 2024**, under the UK's new rules, **the bank has to refund her.** Banks are now *legally liable* for exactly the scam their systems can't see — because those systems score **one transaction at a time**, and **the scam is the *sequence*.**"

**[0:30 — LIVE DEMO. Switch to the bank (`:9000`). This is the pitch — let it breathe.]**
> "This is Diakrisis. I'm the victim. First I break the term deposit — *[click → step-up → confirm]* — funds freed. Now I sweep them to a brand-new 'safe account', €4,850 — *[click Send]*.
> **[the HOLD screen appears — point at it]**
> It didn't just block a number. It says: *'This looks like an **account-liquidation scam** — funds were just freed and are now going to a brand-new payee — we've paused it to protect you.'*
> It **named the scam to her, in plain English. No code. No score. One button: Cancel.** It linked the deposit-break to the sweep, seconds apart — **across two separate actions** — in **under 50 milliseconds.**"

**[1:45 — WHY IT'S CLEVER & SOUND. Switch to the analyst console (`:5173`).]**
> "Two faces. The customer never sees a score. The fraud analyst sees **everything** — *[console]* — the kill-chain timeline, all 27 signals, and **three independent judges that agree**: a deterministic rules engine, a machine-learning model, and a local AI co-judge.
> Here's the key choice: **any one of them — or all of the ML — can fail, and the engine still makes a fully-explainable, regulator-attributable decision in under 50ms — because the deterministic rules engine is the *spine*, and the ML and AI are weight-capped advisors that can never override it.**
> And it compounds: a payee flagged on **one** bank warns the **next** — a network the per-bank incumbents can't copy."

**[2:30 — HONEST CLOSE + ASK.]**
> "What's real: it runs end-to-end, live, right now — **30-of-30 regression green**, full test suite passing. What we're honest about: it runs on Docker today — the AWS CloudFormation is **written**, the data layer is **free-tier-ready** — deploying is a *step*, not a rebuild. And the ML is trained on public **card**-fraud data; the **APP-scam** intelligence is the explainable **rules** — deliberately, because no labelled APP data exists for *anyone*.
> Who pays? The **fraud chief who's now personally liable.** Diakrisis **watches the whole sequence, names the scam to the victim, and proves every decision to the regulator.** That's the layer the incumbents leave on the table."

*(~430 words + ~75s of live clicking ≈ 3:00. Practice the demo twice so the reset/click order is muscle memory — see `DEMO-JOURNAL.md`.)*

---

## 🛡️ THE 2-MINUTE Q&A CHEAT-SHEET

*Answer in 2–3 sentences. The pattern that wins: **concede honestly → then show the real depth behind it.***

### ☁️ AWS / cloud (Nikiforos — AWS SA)
**"How does this actually go to AWS? Your README implies zero code change."**
> "Honest answer: ~10 lines. The endpoint override is currently unconditional with dummy local creds — production makes `endpointOverride` conditional and swaps to the Fargate task-role credential chain. The *hard* part is done: the AWS SDK v2 enhanced client, 16 `@DynamoDbBean` entities, the pk/sk schema, the **TTL attribute IS the 72-hour kill-chain decay**, and deployable CloudFormation. Compute and the OpenSearch/Bedrock swaps sit behind interfaces that already exist — config, not rewrite."

**"p99 under real load? Java cold-start vs the 50ms SLA?"**
> "The <50ms is the in-process **deterministic** decision, measured — the co-judge is *outside* it, time-boxed, advisory, so the SLA never depends on the LLM. End-to-end over API Gateway, my honest p99 target is low-hundreds-of-ms, and I'd run **Fargate** (warm) for steady bank traffic to dodge Java cold start. I have **not** load-tested on AWS yet — that's the honest gap."

**"Multi-tenant isolation / keys / audit for a regulated bank?"**
> "Single-tenant by design for the demo, and I'll own that. Production: tenant-id prefix in the pk + per-bank stages, KMS CMKs per tenant, JWT secret to Secrets Manager with asymmetric signing (not the shared HS256 in compose), and decisions to S3 Object-Lock as the tamper-evident, attributable audit log. Architecture-ready, not built — I'd rather tell you that than overclaim SOC2 on a weekend."

### 🔬 ML rigour (Andreas — academic)
**"Be honest — PR-AUC 0.42 is a weak model. Doesn't your ML basically suck?"**  ⭐ *(most-likely question — own it confidently)*
> "As a *standalone* classifier, 0.42 is modest — I won't pretend it's state-of-the-art. But three things. **One:** at a 3.5% base rate that's a **~12× lift**, and ROC-AUC is **0.83** — that's real discrimination, not noise. **Two:** it's **deliberately handicapped** — only the **16 features we can compute live in under 50ms** (train/serve parity), not the 433-column entity-engineered Kaggle models you can't run in the hot path. **Three:** it's a **capped advisor — 18 of ~100 points — that never decides;** the deterministic, explainable rules engine owns the verdict. The product isn't the model: APP-scam detection is the **rules**, because no labelled APP data exists for *anyone*. So a modest model literally **can't hurt us** — and we made it falsifiable by shipping the scored validation set."

**"You're trained on IEEE-CIS *card* fraud but you sell *APP-scam* detection. What is 0.42 evidence FOR?"**
> "Exactly one thing: that velocity/recency/amount-vs-own-history *anomaly shape* is learnable from serve-time features — 12× lift over the 3.5% base rate, calibrated. It is **not** evidence we catch APP scams — *nothing* is, because no labelled APP corpus exists. APP detection is carried by the deterministic **kill-chain rules**, evaluated as rules. M1 is capped at 18/100 precisely so a domain-transfer model can **never** be the decider."

**"Are your model numbers even self-consistent? Which document is the real model?"**
> "The artifacts are ground truth — **300 trees, unweighted; recompute PR-AUC 0.422 and ROC-AUC 0.834 from the committed `val_scores.csv` yourself** (88,581 scored rows). Every doc — model card, README, feature spec — now matches them. We'd rather you catch a stale doc than a wrong metric, which is why we shipped the scored oracle to make the headline falsifiable." *(✓ all docs now reconciled to the artifacts.)*

**"Recall 0.23 at precision 0.83 — you miss ~77% of fraud. Defend acting on that. False-positive cost?"**
> "That's exactly why M1 **never blocks** — it's one capped signal that adds a percentile nudge; a BLOCK needs the deterministic kill-chain to fire. False positives are handled three ways: a **CONFIRM** band that *asks* instead of blocking, **four-eyes** human review before any high-value block stands, and the customer is **never silently blocked** — they see the named scam and a Cancel button. So a wrong M1 costs a friction prompt, not a frozen account."

### ⚙️ Engineering (Elena — CTO)
**"Is the kill-chain a real temporal mechanism or a hard-coded if-statement for the demo account?"**
> "Real account *posture*. The deposit-break persists `fundsFreedEur` + `lastDepositBreakEpochMs`; K1 computes freed/amount with exponential decay over a 7-day window (longer than the 72h velocity window — real liquidations drain *days* later). The typology only fires on a **conjunction**: K1>0.6 AND brand-new payee AND it drains the balance — account-agnostic. The `t5b` golden-path test drives it through the real engine."

**"Prove the ML is actually firing, not silently stubbed at zero by your graceful degradation."**
> "Fair — degradation can't be a fig leaf, so we expose it: `M1Scorer.isLoaded()` and the boot log say whether the live model is in play ('M1 model loaded — 16 features'), and the 16-feature schema is bound to `columns.txt` so train/serve parity is *verifiable*. The fallback (KdTree) gives the *same* cosine ranking, so it's a true fallback, not a different answer. And M1's PR-AUC is *only* 0.42 — which is exactly why it's capped and the rules own the decision."

### 💡 Business (Panis — innovation)
**"Who pays, and why not just ask Featurespace/Feedzai to add a 'sequence' rule?"**
> "We don't sell detection — incumbents own that. We sell the layer they leave to the bank: the **customer-facing decision and the in-the-moment intervention**, plus **regulator-defensible explainability** the black-box incumbents can't give. Buyer: the fraud owner now personally liable under PSR. We start at neobanks/PSPs where integration is shallow and liability is sharpest. The moat is **X1 cross-account reputation** — a *network*, not a feature: every bank that joins makes it smarter, which a per-bank incumbent structurally can't copy."

**"Coached victims are told to ignore warnings. What evidence a plain-English pause changes the outcome?"**
> "We don't claim a warning alone beats a determined coach — that's why it's a **freeze**, not a nudge: the engine never auto-sends, the only button is Cancel. Naming the *specific* scam injects a fact the coach didn't script for — different from a generic 'are you sure?' they're pre-inoculated against. We'd validate it on cancel-rate and reimbursement-claim reduction in a pilot. And even when the victim proceeds, we've created a **timestamped, regulator-defensible record** the bank intervened — which under PSR liability has direct financial value."

---

### The one-liner if you only get one sentence
> *"Diakrisis is the only fraud layer that watches the whole **sequence** — it linked a savings-liquidation to the follow-on sweep and **paused it by naming the scam to the victim in plain English** — a deterministic, regulator-explainable engine with weight-capped ML and AI advisors, deciding in under 50ms, that gets smarter with every bank that joins."*
