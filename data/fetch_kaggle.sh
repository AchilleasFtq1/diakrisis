#!/usr/bin/env bash
# Input fetch (STEP A) — downloads raw PUBLIC datasets from Kaggle.
# Requires: KAGGLE_API_TOKEN env var (token with download scope) and, for IEEE-CIS,
# the competition rules accepted at https://www.kaggle.com/c/ieee-fraud-detection/rules
# ULB creditcard is NOT fetched here — already local from OpenML (data/raw/ulb/creditcard.csv).
# Idempotent: sections are skipped when their target file already exists.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
RAW="$ROOT/raw"
KAGGLE="$ROOT/../.venv/bin/kaggle"
mkdir -p "$RAW"

if [ -f "$RAW/ieee-cis/train_transaction.csv" ]; then
  echo "== IEEE-CIS already present, skipping =="
else
  echo "== IEEE-CIS fraud detection (competition, ~600MB) =="
  mkdir -p "$RAW/ieee-cis"
  "$KAGGLE" competitions download -c ieee-fraud-detection -p "$RAW/ieee-cis"
  unzip -o -q "$RAW/ieee-cis/ieee-fraud-detection.zip" -d "$RAW/ieee-cis"
fi

if [ -f "$RAW/paysim/PS_20174392719_1491204439457_log.csv" ]; then
  echo "== PaySim already present, skipping =="
else
  echo "== PaySim synthetic mobile-money fraud (~180MB) =="
  mkdir -p "$RAW/paysim"
  "$KAGGLE" datasets download -d ealaxi/paysim1 -p "$RAW/paysim" --unzip
fi

if compgen -G "$RAW/baf/*.csv" > /dev/null; then
  echo "== Bank Account Fraud already present, skipping =="
else
  echo "== Bank Account Fraud (NeurIPS 2022, Feedzai) =="
  mkdir -p "$RAW/baf"
  "$KAGGLE" datasets download -d sgpjesus/bank-account-fraud-dataset-neurips-2022 -p "$RAW/baf" --unzip
fi

if [ -f "$RAW/sparkov/fraudTrain.csv" ]; then
  echo "== Sparkov already present, skipping =="
else
  echo "== Sparkov simulated card transactions =="
  mkdir -p "$RAW/sparkov"
  "$KAGGLE" datasets download -d kartik2112/fraud-detection -p "$RAW/sparkov" --unzip
fi

echo "== Verify =="
RAW_DIR="$RAW" "$ROOT/../.venv/bin/python" - <<'PY'
import os
import pandas as pd
from pathlib import Path
raw = Path(os.environ["RAW_DIR"])
checks = {
    "ieee-cis/train_transaction.csv": None,
    "ulb/creditcard.csv": None,
    "paysim/PS_20174392719_1491204439457_log.csv": None,
    "sparkov/fraudTrain.csv": None,
}
for rel in list(checks):
    p = raw / rel
    if p.exists():
        df = pd.read_csv(p, nrows=5)
        print(f"OK   {rel}: {df.shape[1]} cols")
    else:
        print(f"MISS {rel}")
# BAF ships multiple variant CSVs — just list them
baf = raw / "baf"
for f in sorted(baf.glob("*.csv")):
    print(f"OK   baf/{f.name}")
PY
