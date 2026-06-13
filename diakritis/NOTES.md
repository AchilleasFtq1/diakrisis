# Event day — Sat 13 Jun 2026, 12:30 → Sun 12:30

Models live in `models/` and load straight into the engine — no retraining on
the path. The data-provenance slide cites "M1 + exemplar index from public data
(IEEE-CIS), pipeline and metrics in MODEL_CARD.md". The deterministic pipeline
(`training/TrainM1.java`) reproduces the numbers in ~53 s and stays available as
a live flex if a judge asks to see a retrain.

## 0) Boot (5 min, before anything)

```bash
HOME=$HOME nohup ollama serve &        # native — brew service panics on $HOME
docker start qdrant                    # collection fraud_exemplars already loaded
curl -s localhost:6333/readyz          # expect: all shards are ready
ollama list                            # expect: gemma4:e2b
```

Then: `git init`, copy in the prepared `.gitignore`, first commit at window open.
Commit small and often — repo history is a submission requirement.

## 1) What is ALREADY DONE (do not redo)

- Datasets verified: Berka 1,056,320 / IEEE-CIS 590,540 (3.50%) / ULB 284,807
- `models/m1/m1.model` — Smile GBT, PR-AUC 0.4220 calibrated, ROC 0.8339
- `models/m1/isotonic.csv`, `percentiles.csv`, `columns.txt` (feature contract)
- `models/m2/m2_scaler.json` + Qdrant `fraud_exemplars` (44,538 pts, p95 2.8 ms)
- Co-judge validated: gemma4:e2b, 24/24 JSON, p50 944 ms under 1200 ms budget
- All numbers + caveats: `models/README.md`, `MODEL_CARD.md`, `FEATURE_SPEC.md`

## 2) The day's REAL work (SDD §15 plan, ML hours now freed)

| Hours | Build |
|---|---|
| 0–1 | Repo, modules (`etl/`, `engine/`), domain records, `Weights.java` |
| 1–3 | etl: Berka → `features.sqlite` (payee tables, baselines, batch history). **First light: POST /decision returns a scored decision on real history** |
| 3–6 | Signals B/A/V/C + runtime state + logical amount + fixtures |
| 6–8 | G/D/K + observation store + posture + P2P alias + P1 |
| 8–10 | Typologies + bands + rail shift + approval routing + SCA + lifecycle SM |
| 10–12 | Mass-payment batch pipeline (per-line loop + MP signals + quarantine) |
| 12–14 | Mini-bank UI: all flows + approver inbox + 5 renderings |
| 14–16 | Ops dashboard + WebSocket + counters + posture chips |
| 16–19 | **ML integration only (was training — now ~freed):** see §3. Banked time → buffer/polish |
| 19–21 | Sandbox sessions, hardening, golden path green, latency print |
| 21–24 | Polish, record fallback, deck ≤5 slides, rehearse ×3 to 2:45 |

## 3) ML integration checklist (the only ML work left)

1. **`Features.java`** — port the 16 formulas from `models/FEATURE_SPEC.md`.
   MUST match `models/m1/columns.txt` order — `m1.model` and the M2 scaler are
   bound to it. Unit test: row → vector → assert against 2–3 known
   `val_scores.csv` entries.
2. **`M1Signal`** — deserialize `m1.model` (plain Java `ObjectInputStream`,
   Smile 3.1.1 on classpath), apply `isotonic.csv` step function (binary search,
   clip below), percentile-rank via `percentiles.csv`, weight cap 18.
3. **`ExemplarIndex`** — interface + two impls: Qdrant (gRPC :6334, k=25 cosine)
   and Smile KDTree over the same vectors (**build the fallback FIRST**).
   Vector = raw features → standardize per `m2_scaler.json` → L2-normalize.
   M2 = Σ(w·fraud)/Σw, w = 1/(dist+1e-6), weight cap 12.
4. **`AiCoJudge`** — contract is in `models/README.md`: warm-up call AT BOOT
   (mandatory — cold model = 24/24 UNAVAILABLE cascade), 1200 ms hard timeout,
   temperature 0, num_predict 48, validate JSON keys, UNAVAILABLE on any miss.
5. Surface M1 contribution + feature importances + "resembles N confirmed fraud
   cases (avg distance X)" on the ops panel (importances in metrics_m1_smile.json).

## 4) Demo + pitch (memorize)

- Golden path T1–T13 (SDD §12) asserted green before every commit after hour 12
- Demo accounts: business **3834** (277 transfers, 5 payees, REAL second
  authorized person), retail 7819 (no dual access — don't use for approval scene)
- Three numbers: **12× lift** (PR-AUC 0.42 vs 0.035 base) · **83% precision**
  at strict threshold ("when it points, it's right") · **engine survives at
  model weight 0** (resilience principle)
- Honest lines said unprompted: no public APP-scam corpus exists (typologies
  carry APP semantics; M1 carries learned anomaly shape) · models built from
  public data (IEEE-CIS), disclosed, pipeline reproducible in 53 s

## 5) Cut order if behind (pre-decided — do not re-debate at 3 AM)

Gemma co-judge → M2/Qdrant (KDTree keeps the signal) → typologies 3–4 →
EL templates. **Never cut:** runtime state, K1/B1/B2/A2, typologies 1+5+6,
approval flow, kill-chain + batch demo scenes, golden path.

## 6) Known traps (each cost ≥30 min — don't rediscover them)

1. Co-judge without boot warm-up → every call UNAVAILABLE (silently!)
2. brew `ollama` service crashes on `$HOME` — use the nohup line above
3. Ollama in Docker = CPU = 8.4 s/call — native only for the LLM
4. Smile GBT has NO sample weights — fine on IEEE-CIS imbalance; if you ever
   touch ULB in Java, oversample fraud ×60 or it trains a constant model
5. Working directory drift with Maven — always `cd` with absolute paths
6. `isotonic.csv` predict = largest threshold ≤ score (clip below range)
