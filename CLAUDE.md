# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

Diakrisis is a fraud-detection engine (hackathon build window Sat 12:30 → Sun 12:30). This directory is an **Eclipse workspace** (`.metadata/` at root) holding two distinct, currently-unlinked pieces:

- **`diakritis/`** — the Spring Boot application (the live engine). Right now it is a bare Spring Initializr skeleton: only `DiakritisApplication.java` exists. This is where the engine, signals, and API get built.
- **`diakrisis-models/`** — **not a build module.** Pretrained ML artifacts plus the standalone training/eval source. It defines the contracts the Spring app must honor at serve time. Nothing here is on the Gradle classpath; the `.java` files are reference implementations to be ported into `diakritis/`.

> Naming gotcha: the product/SDD is **"diakrisis"** but the Spring module, root package, and artifact are **"diakritis"** (`com.cy.diakritis`). The original package `com.cy diakritis` was invalid; it became `com.cy.diakritis` (group `com.cy` in `build.gradle`). Don't "fix" the spelling — match whatever a given file already uses.

## Build / run / test

All commands run from `diakritis/` (the only Gradle project):

```bash
cd diakritis
./gradlew build            # compile + test + package
./gradlew bootRun          # run the Spring Boot app
./gradlew test             # full test suite
./gradlew test --tests 'com.cy.diakritis.DiakritisApplicationTests'   # single test class
./gradlew test --tests '*.SomeTest.some_method'                       # single test method
```

- **Java 26** (toolchain pinned in `build.gradle`), **Gradle 9.5.1** (wrapper), **Spring Boot 4.1.0**, **JUnit 5**.
- `diakrisis-models/training/*.java` are **not** built by Gradle. They depend on the **Smile** ML library and were designed to run from a separate Maven `trainer/` module (`mvn -q exec:java -Dmain.class=TrainM1`) that does not exist in this workspace yet. Paths inside `TrainM1.java` (`../python/artifacts/...`, `../models/m1`, `../data/raw/ulb/...`) are relative to that future module, not to this repo.

## The model contract (read before touching features or scoring)

The engine combines three signals; weights are capped so any one (or all ML signals) going to zero leaves a fully-functional deterministic engine. Authoritative specs live in `diakrisis-models/`:

- **`FEATURE_SPEC.md` + `m1/columns.txt`** — the 16 features and their exact formulas/order. Any `Features.java` you write in `diakritis/` **must emit exactly these columns in this order** — `m1.model` (Smile GradientTreeBoost) and the M2 scaler are bound to it. Train/serve parity is the whole design constraint; the model is deliberately limited to features computable at serve time.
- **`MODEL_CARD.md`** — M1 data/split/metrics/caveats. Time-based 70/15/15 split; isotonic-calibrated; PR-AUC ≈ 0.41. Percentile-ranked, weight-capped at 18.
- **M1 serve path**: raw GBT score → apply `m1/isotonic.csv` (PAV step function) → percentile-rank via `m1/percentiles.csv`.
- **M2 serve path**: standardize the same 16 raw features with `m2/m2_scaler.json` → L2-normalize → cosine k-NN (k=25) against Qdrant collection `fraud_exemplars` → signal = distance-weighted fraud share, weight cap 12.
- **Co-judge** (advisory, outside the <50 ms decision path): native Ollama `POST localhost:11434/api/generate`, model `gemma4:e2b`, `format:"json"`, hard timeout 1200 ms → on any miss return UNAVAILABLE and proceed. **Warm-up at boot is mandatory** (one untimed `num_predict:1` call; cold load ~13 s). Full contract + prompt template in `diakrisis-models/README.md`.

## Local infrastructure for the ML paths

- **Qdrant** (M2 vector search): `docker start qdrant` — collection `fraud_exemplars`, 44,538 points.
- **Ollama** (co-judge): brew's service wrapper panics on `$HOME`; start it as `HOME=$HOME nohup ollama serve &`. Run **native** (Metal); Docker is CPU-only (~8.4 s/call vs ~950 ms).

## Working agreements

- The Spring app is JVM-only by design (SDD mandates pure-JVM/Smile). Python only ever existed as a pre-event rehearsal harness and has been removed by request — do not reintroduce a Python runtime step. The Saturday plan is to port `TrainM1.java` and `Features.java` so the pipeline reads IEEE-CIS directly.
- `diakrisis-models/m1/*` and `m2/*` are committed artifacts treated as test oracles (`val_scores.csv` drives case tests). Don't regenerate or overwrite them casually.
