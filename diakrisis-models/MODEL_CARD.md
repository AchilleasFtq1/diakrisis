# Model Card — M1 fraud scorer

> The `m1.model` is built inside the event window (Sat 12:30 → Sun 12:30) with this pipeline.
> **Every number below is reproducible from the committed `m1/val_scores.csv` (88,581 scored
> validation rows, with labels) and `m1/metrics_m1_smile.json`** — recompute the PR-AUC, ROC-AUC and
> calibration bins yourself. Seeds fixed at 13.

## Data
- **Training**: IEEE-CIS Fraud Detection (Kaggle competition), `train_transaction.csv`,
  590,540 rows, 3.50% fraud. Real labeled card-transaction fraud.
- **Benchmark**: ULB creditcard (OpenML #1597), 284,807 rows, 0.173% fraud — run
  through the identical pipeline discipline, reported as one appendix number.
- No Berka rows are used for supervised training (Berka has no fraud labels);
  Berka supplies the engine's per-account behavioral baselines only.

## Features (16 — the train/serve intersection)
Only features the engine can compute at serve time from (action, runtime state,
observation store). Raw units, **no normalization** (trees split on thresholds).

| Group | Features | Serve-time source |
|---|---|---|
| Amount | `amt_log`, `amt_ratio` (vs own card history, leakage-free expanding mean, clip 50) | action + account history |
| Time | `hour_sin/cos`, `dow_sin/cos` | action timestamp |
| Velocity | `c1_log`, `c13_log`, `c14_log` | runtime counts |
| Recency | `d1`, `d4`, `d10`, `d15` (missing → −1), `d_miss_count` | time-since-last; missing-as-signal |
| Counterparty proxy | `is_free_mail`, `email_missing` | counterparty type |

Sparse D-columns (D6–D9, D12–D14, 6–13% populated) deliberately excluded.

## Training discipline
- **Split**: time-based 70/15/15 (train / calibration / validation) on `TransactionDT` —
  train on earlier, validate on later; no leakage.
- **Imbalance**: no sample weighting (Smile's `GradientTreeBoost.fit` takes none); the 3.5% base
  rate is handled downstream by isotonic calibration, percentile ranking, and the 18-point weight cap.
- **Sweep**: configs (lr × depth × trees) selected on the **calibration** fold; validation untouched
  until final scoring. Final config: lr=0.1, depth=6, **300 trees**, maxNodes 31, nodeSize 20
  (sklearn parity: 0.419 Smile vs 0.423 sklearn PR-AUC). Features, not hyperparameters, are the ceiling.
- **Calibration**: isotonic regression fit on the calibration fold.
- **Baseline**: L2 logistic regression on the same vectors.

## Results (validation fold, untouched)
| Model | PR-AUC | ROC-AUC |
|---|---|---|
| M1 GBT (isotonic-calibrated) | **0.422** | 0.834 |
| LR baseline (L2) | 0.377 | 0.803 |
| ULB benchmark (same pipeline) | 0.774 | 0.965 |

Base rate 3.48% → M1 PR-AUC is a ~12× lift from serve-computable features only.
(Kaggle leaderboard numbers use all 433 columns + entity engineering and are not
comparable — this model is constrained to train/serve parity by design.)

**Operating points** (calibrated probability): 0.3 → precision 0.70 / recall 0.32;
0.5 → 0.74 / 0.29; 0.8 → 0.83 / 0.23.

**Calibration** (predicted → observed fraud rate, validation fold): 0–0.1 → 1.8% (n=83,964),
0.1–0.3 → 17.9% (n=3,227), 0.3–0.5 → 44.3% (n=183), 0.5–0.7 → 48.5% (n=291), ≥0.7 → 81.7% (n=916).
Monotone across the well-populated low and high bands; **near-flat in the sparse mid-band (n=183/291)**,
so the reliable probability mass is at the low and high ends — which is why M1 only ever *adds*
capped evidence and never decides alone.

**Top features** (permutation importance on validation): `c1_log`, `c14_log`,
`d10`, `amt_log`, `c13_log`, `amt_ratio` — velocity and recency dominate; the
model learned behavioral signals, not amount alone.

## How the engine consumes M1
Percentile-ranked against the training-score distribution (`m1_score_percentiles.npy`),
weight-capped at 18 of ~100 — one signal among 25, never the sole decider.
Weights → 0 leaves the deterministic engine fully functional (resilience principle).

## Caveats (say unprompted)
- **Domain transfer**: trained on card-fraud labels; the *anomaly shape* (velocity,
  recency, amount-vs-history) transfers to bank actions, but APP-scam semantics
  live in the typology rules and kill-chain posture, not in this model.
- No public corpus of labeled APP-scam transfers exists; same epistemic position
  as every vendor — the difference is saying it first.
- Recall at high precision is modest (0.23 @ 0.83 precision) — by design the
  engine's explainable signals carry coverage; M1 adds learned shape on top.
- The model is a pure-JVM Smile GradientTreeBoost, trained in the window per the SDD.
