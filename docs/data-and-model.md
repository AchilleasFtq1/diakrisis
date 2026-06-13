# Data & Model — what Diakrisis stores, and when (not) to retrain

A plain-language explainer for "where does the data come from?", "is that the bank's customer
transactions?", and "if I load other data, do I have to retrain the model?". Short version:
**the data is real bank transaction history, and swapping it does NOT require retraining — you
re-run the ETL.** Here's the why.

---

## The one idea that answers everything: two layers

Diakrisis decides with two layers, and only one of them is a trained model:

1. **The deterministic engine (the foundation, ~85% of the score).** Pure functions over data —
   signals like "new counterparty", "amount anomaly", "balance drain", "funds freed recently",
   "unfamiliar geo". They **derive** every judgment *live* from whatever data is loaded. They
   were never trained on anything; they compute against the current data each time.
2. **The ML signals (a bonus, weight-capped ~15%).** `M1` (a trained gradient-boosting model)
   and `M2` (nearest-neighbour over fraud examples). These *are* pre-trained — and they're
   **loaded, never retrained** by this system.

Because the foundation is the deterministic layer, **the system adapts to new data by re-reading
it, not by relearning it.**

---

## What the data IS

| Data | Source | Real? | Used as |
|---|---|---|---|
| **Behavioural baselines** (per-account payment history: who they pay, how often, how much, balances, standing orders) | **Berka (PKDD'99)** — real Czech-bank transactions, ~1.05M rows | **Real bank customer transactions** | reference the engine reads at decision time |
| **M1 model** | IEEE-CIS card-fraud (Kaggle) | real labelled fraud | the pre-trained ML score |
| **M2 exemplars** (44k fraud/legit feature vectors) | IEEE-CIS | real | nearest-neighbour "resembles N fraud cases" |
| 3 disclosed constructs (CoP names, one term deposit, one escalation series) | constructed on top of real accounts | marked `source="CONSTRUCTED"` | only where Berka has no such field |

So **yes — the seed is the bank's customer transaction history** (Berka). It's the "what's normal
for this customer" baseline, not model training data.

---

## Where it lives: DynamoDB vs Qdrant

- **DynamoDB** — exact-key records: *what we know about an account/counterparty* (histories,
  balances, observations, posture, decisions). Answers "is this payee new? what's normal here?
  was this counterparty flagged elsewhere?" Re-loaded by the ETL.
- **Qdrant** (or the in-JVM KDTree fallback) — only the 44k fraud-exemplar **vectors**. Answers
  one question: *does this transaction look like known fraud?* (k-NN in feature space).

DynamoDB = facts and state. Qdrant = similarity. Neither is "the model"; M1 is the model.

---

## The question you'll get asked: "If I put other data, do I retrain?"

**No — re-run the ETL.** Concretely:

- **Swap the behavioural data** (Berka → your bank's real transactions): point the ETL at it and
  reload DynamoDB. The deterministic engine **immediately** adapts — "new counterparty",
  "anomalous amount", "funds freed" are all computed *per account* against whatever is loaded.
  **Zero retraining.** This is the per-bank self-calibration: the engine is data-driven, not
  data-memorised.
- **M1 / M2 keep working unchanged.** They score the action's 16 features through the
  already-trained model. They encode the general *shape* of fraud (velocity, recency,
  amount-vs-history) — they don't need your data to function. New data doesn't break them.

So the answer in one line:
> *"New transaction data just gets re-ingested by the ETL into the feature store — the
> deterministic engine recomputes every signal against it live, so no retraining is needed. The
> ML model is a pre-trained, capped add-on; the engine is fully functional without it."*

---

## When you WOULD retrain (optional, never required)

Only as an **enhancement**, to make the ML signal sharper on your population:

- **Feedback loop (§9.5):** every outcome is a label — cancel a HOLD = confirmed-save,
  release-after-expiry = false-positive. Those counters drive **per-bank calibration** of the
  weights (the `decisions → outcomes → calibration` arrow). This tunes a *number table*, not the
  model.
- **Model retrain (§16):** if you want M1/M2 to learn your bank's specific fraud labels, schedule
  a retrain (e.g. SageMaker) on your data. Optional.
- **Resilience principle:** with M1/M2 at weight 0 (or unloadable), the deterministic engine is
  **fully functional**. The ML is ~15% of the score; the data-driven logic is the foundation. So
  retraining is never a blocker — it's an upgrade.

---

## Cheat sheet (what to say)

| They ask | You answer |
|---|---|
| "Is that the bank's transactions?" | Yes — real Berka bank history is the behavioural baseline; the engine reads it live. |
| "If I load my own data, do I retrain?" | No — re-run the ETL into the feature store; the deterministic engine adapts automatically. |
| "Then what's the model for?" | M1/M2 add a learned fraud-*shape* signal on top, capped at ~15%; pre-trained, loaded not trained. |
| "What if the model is wrong/missing?" | Engine still works fully (resilience) — ML weight can go to 0. |
| "Can it get better over time?" | Yes — the feedback counters calibrate weights per bank; optional SageMaker retrain for the ML. |
| "Where's the vector DB fit?" | Qdrant only holds fraud-example vectors for the "resembles N fraud cases" signal; everything else is DynamoDB. |
