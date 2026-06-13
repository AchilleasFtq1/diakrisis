# Diakrisis Fraud-Ops Console

The analyst-facing UI for the Diakrisis scam-decision engine — the operational counterpart to the
customer bank (Meridian), where the engine's **scores, typologies, signal breakdown and kill-chain
timeline** are exposed. Built from the Claude Design file (`kill-chain-dashboard`), in the dark
"control-room" theme.

**Stack:** React 19 · Vite 6 · TypeScript · Tailwind v4 · TanStack Query v5 · React Router v7.

## Run

```bash
cd ops-console
npm install
npm run dev          # http://localhost:5173
```

It calls the Diakrisis **gateway** (default `http://localhost:8080`, override with
`VITE_GATEWAY_URL`). Bring the platform up first (`cd diakritis && docker compose --profile demo up
-d`), then sign in as **`ops-user` / `demo`** (or `admin` / `admin`).

Drive a few payments in the demo bank (`:9000`) — including the deposit-break → drain **kill-chain**
— to populate the live feed.

## Screens

`/login` · `/overview` (KPIs + live decision feed) · `/decisions/:id` (signal bars + kill-chain
timeline) · `/approvals` (four-eyes) · `/accounts/:id` (posture + observations).

## Data

Everything is real, from the gateway: `/ops/feed`, `/ops/counters`, `/ops/decisions/{id}`,
`/ops/accounts/{id}`, `/ops/approvals`, and `/actions/{id}/approve|reject`. Any field the API can't
provide renders a **`⚑ needs review`** flag — never fabricated data.
