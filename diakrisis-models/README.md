# Diakrisis — ML model artifacts

> The artifacts are built inside the build window
> (Sat 12:30 → Sun 12:30) with `training/TrainM1.java` — the pipeline is
> deterministic, so a rebuild reproduces these numbers.
> M1 retrains in ~53 s; the Qdrant load takes ~6 s.

## Layout (Java-only)

| Path | What | Consumer |
|---|---|---|
| `m1/m1.model` | **Smile GradientTreeBoost** (Java serialization) | engine `M1Signal` |
| `m1/isotonic.csv` | PAV calibration step function (threshold,value) | apply after raw GBT score |
| `m1/percentiles.csv` | calibrated train-score percentiles 0–100 | percentile-rank M1, weight cap 18 |
| `m1/columns.txt` | feature order contract — `Features.java` must emit exactly this | train/serve parity |
| `m1/val_scores.csv` | calibrated score per validation row | case tests |
| `m1/metrics_m1_smile.json` | full eval (PR-AUC, ops, calibration, ULB) | model card / appendix |
| `m2/m2_scaler.json` | StandardScaler mean/scale (fit on train fold ONLY), 16 columns | serve: standardize → L2 → cosine k-NN k=25 |
| `training/TrainM1.java` + `Isotonic.java` + `Metrics.java` | the full training/eval pipeline (module: `trainer/`) | Saturday rebuild |
| `MODEL_CARD.md` | data, features, split, metrics, caveats | submission |

Qdrant collection **`fraud_exemplars`** (44,538 points: 14,538 fraud + 30,000
legit train-fold exemplars, payload `{fraud: 0|1}`) is loaded in the local
`qdrant` container volume. M2 signal = distance-weighted fraud share among
k=25 cosine neighbors, weight cap 12.

## Verified numbers (untouched time-split validation fold)

- **M1**: PR-AUC **0.4220** calibrated, ROC-AUC 0.8339 (sklearn cross-check: 0.4229/0.8349, score corr 0.92 — not a library artifact)
- **Operating points**: 0.3 → P 0.698 / R 0.315 · 0.5 → 0.737/0.288 · 0.8 → 0.832/0.234
- **Calibration**: monotone; bin [0.7–0.9) → 81.7% observed fraud
- **ULB benchmark**: PR-AUC 0.7743, ROC-AUC 0.9650
- **M2**: PR-AUC 0.196 / ROC 0.725 vs 3.1% base; Qdrant↔exact-fallback parity corr 1.0000; p95 query 2.8 ms
- **Case test, 1000 unseen txns**: top-50 by ML score = 42% fraud (4% base);
  strict threshold 8/8 pure, zero false alarms; loud band (≥20/30) = 22.9% fraud
- **Co-judge**: 24/24 valid JSON, p50 944 ms, max 1144 ms (budget 1200 ms);
  HOLD/BLOCK on 5/8 loud cases — matching the 5 real frauds in that sample

## Co-judge contract (for the Java `AiCoJudge` impl — reference impl was Python, deleted by request)

- Endpoint: native Ollama `POST http://localhost:11434/api/generate`,
  model **`gemma4:e2b`**, `stream:false`, `format:"json"`,
  `options: {temperature: 0, num_predict: 48}`, hard timeout **1200 ms** →
  return UNAVAILABLE (engine proceeds unaffected).
- **Warm-up at engine boot is mandatory**: one untimed call (`prompt:"ok"`,
  `num_predict:1`, ~13 s cold). A budgeted call against a cold model aborts the
  load and EVERY later call also misses (observed: 24/24 UNAVAILABLE).
- Prompt template (validated 24/24 parse, verdicts track ground truth):

  ```
  Fraud co-judge. Reply ONLY minified JSON {"score":0-100,"decision":"ALLOW|CONFIRM|HOLD|BLOCK","reason":"<max 6 words>","agreement":true|false}

  Action: <amount, rail, counterparty age, recency facts, device/geo>
  Engine: <score, band, typology, key signal values>
  ```
- Validate keys `score/decision/reason/agreement` + decision ∈ enum; anything
  else → UNAVAILABLE.

## Known constraints (disclose, don't hide)

- **Smile GBT has no sample weights.** IEEE-CIS (3.5% positive) trains fine
  unweighted; ULB (0.17%) degenerates to a constant — the ULB benchmark
  oversamples fraud ×60 in its train fold (tree equivalent of weighting).
- **Co-judge budget 1200 ms**, not the brief's 600: e2b's floor on the M4 is
  ~950 ms. Advisory-only, outside the <50 ms decision path.
- **Ollama runs natively** (Metal; Docker = CPU-only = 8.4 s/call). brew's
  service wrapper panics on `$HOME`: start `HOME=$HOME nohup ollama serve &`.
  Qdrant runs in Docker: `docker start qdrant`.
- Recall at high precision ≈ 0.2 — by design; coverage lives in the engine's
  25 explainable signals + typologies (see MODEL_CARD.md).
- `TrainM1.java` currently reads `python/artifacts/features_export.csv`.
  Saturday's port: `Features.java` emits the same 16 features (assert against
  `m1/columns.txt`) straight from IEEE-CIS — removing the last Python step.

## Saturday rebuild (in-window, ~2 minutes compute)

1. `HOME=$HOME nohup ollama serve &` · `docker start qdrant`
2. Port `Features.java` (16 features, order per `m1/columns.txt`)
3. From `trainer/`: `mvn -q exec:java -Dmain.class=TrainM1`
4. Load Qdrant exemplars from the etl module (normalized per `m2/m2_scaler.json`)
5. Case-verify against `m1/val_scores.csv`, commit artifacts with real timestamps
