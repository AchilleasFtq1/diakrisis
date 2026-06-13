# Feature spec — the 16 features `m1.model` was trained on

`Features.java` must reproduce these EXACTLY (same formulas, same order as
`m1/columns.txt`); the model and the M2 scaler are bound to them. Source rows:
IEEE-CIS `train_transaction.csv`, time-sorted by `TransactionDT` ascending.

| # | Column | Formula |
|---|--------|---------|
| 1 | `amt_log` | `ln(1 + TransactionAmt)` |
| 2 | `hour_sin` | `sin(2π·h/24)` where `h = (TransactionDT mod 86400) / 3600` (float) |
| 3 | `hour_cos` | `cos(2π·h/24)` |
| 4 | `dow_sin` | `sin(2π·d/7)` where `d = floor(TransactionDT / 86400) mod 7` |
| 5 | `dow_cos` | `cos(2π·d/7)` |
| 6 | `c1_log` | `ln(1 + C1)`, null C1 → 0 before log |
| 7 | `c13_log` | `ln(1 + C13)`, null → 0 |
| 8 | `c14_log` | `ln(1 + C14)`, null → 0 |
| 9 | `d_miss_count` | count of nulls among {D1, D4, D10, D15} (0–4) |
| 10 | `d1` | `D1`, null → **−1** |
| 11 | `d4` | `D4`, null → −1 |
| 12 | `d10` | `D10`, null → −1 |
| 13 | `d15` | `D15`, null → −1 |
| 14 | `amt_ratio` | `TransactionAmt / mean(prior TransactionAmt of same card1)`, where "prior" = strictly earlier rows in time order (expanding mean EXCLUDING the current row). First occurrence of a card1 (no prior) → **1.0**. Then `min(value, 50)` |
| 15 | `email_missing` | 1 if `P_emaildomain` null else 0 |
| 16 | `is_free_mail` | 1 if `lower(P_emaildomain)` ∈ FREE_MAIL else 0 |

FREE_MAIL = { gmail.com, yahoo.com, hotmail.com, outlook.com, aol.com,
anonymous.com, mail.com, protonmail.com, icloud.com, live.com }

Serve-time mapping (engine): amt from the action; hour/dow from the action
timestamp; c*_log from runtime velocity counts; d* from observation-store
time-since-last (missing = never seen → −1 + miss count); amt_ratio vs the
account's own payment history; email flags ↔ counterparty-type proxy.

Folds used in training (time order): train = rows [0, 70%), cal = [70%, 85%),
val = [85%, 100%). Labels: `isFraud`. Imbalance: unweighted (validated; Smile
has no sample weights). Hyperparameters: 300 trees, maxDepth 6, maxNodes 31,
nodeSize 20, shrinkage 0.1, subsample 1.0.

M2 path on top of these 16 raw features: standardize with `m2/m2_scaler.json`
(x − mean)/scale per column, then L2-normalize the vector, then cosine k-NN
(k=25) against collection `fraud_exemplars`; signal = Σ(w·fraud)/Σw with
w = 1/(cosine_distance + 1e-6).

ULB benchmark (appendix number only): raw columns Time + V1–V28 + Amount,
same fold discipline, fraud oversampled ×60 in the train fold.
