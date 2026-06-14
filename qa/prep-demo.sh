#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# Diakrisis — one-command DEMO PREP.  Run this IMMEDIATELY before you present.
#
# It (1) resets the stack to a pristine state, then WARMS two things that are cold
# on a fresh boot and would otherwise spoil your first live run:
#   • the AI co-judge (Gemma) — a cold model takes ~10s on its first call, so the
#     first decision shows the co-judge as "UNAVAILABLE" instead of agreeing;
#   • the kill-chain decision path (JVM JIT) — the first kill-chain decision runs
#     ~90–115 ms (badge shows "over SLA") until the hot path is JIT-compiled.
# After this script: acc-B is pristine, the co-judge AGREES, and latency is green
# (~30–45 ms) on your very first live kill-chain.
#
# Re-run it right before presenting — Gemma re-cools after a few idle minutes.
# ─────────────────────────────────────────────────────────────────────────────
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
GW="${GW:-http://localhost:8080}"

echo "▸ 1/5  Resetting the stack (pristine accounts; dep-001 ACTIVE)…"
( cd "$ROOT/diakritis" \
  && docker compose --profile demo down -v >/dev/null 2>&1 \
  && docker compose --profile demo up -d  >/dev/null 2>&1 )

echo "▸ 2/5  Waiting for services to be healthy…"
s=""
for i in $(seq 1 60); do
  s=$(docker inspect diakrisis-demo-bank --format '{{.State.Health.Status}}' 2>/dev/null || true)
  [ "$s" = "healthy" ] && break
  sleep 2
done
echo "        demo-bank: ${s:-unknown}"

echo "▸ 3/5  Warming the AI co-judge (Gemma)…"
if ! curl -s -m 4 http://localhost:11434/api/tags -o /dev/null 2>/dev/null; then
  echo "        ⚠ Ollama not reachable on :11434 — start it first:  HOME=\$HOME nohup ollama serve &"
fi
for i in 1 2; do
  curl -s -m 40 http://localhost:11434/api/generate \
    -d '{"model":"gemma4:e2b","prompt":"ok","stream":false,"options":{"num_predict":1}}' \
    -o /dev/null -w "        gemma call $i: %{time_total}s\n" 2>/dev/null || true
done

echo "▸ 4/5  Warming the decision hot paths (JIT) on a sacrificial account…"
# Take the one-time JIT compilation hits (break → kill-chain drain → plain transfer) on a throwaway
# account FIRST, so the diverse feed seeded in step 5 — and your first LIVE kill-chain — are all green.
ADM=$(curl -s -X POST "$GW/auth/login" -H 'Content-Type: application/json' \
      -d '{"username":"admin","password":"admin"}' \
      | python3 -c 'import sys,json;print(json.load(sys.stdin).get("token",""))' 2>/dev/null)
N=$(date +%s)
for i in $(seq 1 3); do
  curl -s -o /dev/null -X POST "$GW/decision" -H "Authorization: Bearer $ADM" -H 'Content-Type: application/json' \
    -d "{\"event_id\":\"wb-$N-$i\",\"account_id\":\"acc-warm\",\"event_type\":\"TERM_DEPOSIT_BREAK\",\"context\":{\"ts\":\"2026-06-14T09:01:00Z\",\"session_id\":\"k$i\",\"channel\":\"MOBILE_APP\",\"ip\":\"203.0.113.7\",\"device\":{\"device_id\":\"dw\",\"platform\":\"IOS\"}},\"payload\":{\"deposit_id\":\"T$i\",\"principal_eur\":9000.0,\"maturity_date\":\"2026-12-01T00:00:00Z\",\"penalty_eur\":120.0}}"
  curl -s -o /dev/null -X POST "$GW/decision" -H "Authorization: Bearer $ADM" -H 'Content-Type: application/json' \
    -d "{\"event_id\":\"wd-$N-$i\",\"account_id\":\"acc-warm\",\"event_type\":\"TRANSFER\",\"context\":{\"ts\":\"2026-06-14T09:02:00Z\",\"session_id\":\"k$i\",\"channel\":\"MOBILE_APP\",\"ip\":\"203.0.113.7\",\"device\":{\"device_id\":\"dw\",\"platform\":\"IOS\"}},\"payload\":{\"counterparty\":{\"addressing\":\"IBAN\",\"value\":\"CY-$i\",\"display_name\":\"D\"},\"amount_eur\":8400.0,\"available_balance_eur\":9000.0,\"rail\":\"INSTANT\"}}"
done

echo "▸ 5/5  Seeding the diverse analyst feed (varied accounts / types / outcomes; records money-protected)…"
python3 "$ROOT/qa/seed-demo-feed.py" 2>&1 | sed -n 's/^/        /p' | tail -6

echo ""
echo "✅ DEMO-READY  —  acc-B pristine · Gemma warm · hot paths warm · analyst feed seeded & diverse."
echo ""
echo "   🏦 Customer bank (Meridian) : http://localhost:9000     → click customer-B"
echo "   🛡  Analyst console          : http://localhost:5173     → ops-user / demo"
echo "   🔌 API / Swagger            : http://localhost:8080/swagger-ui.html"
echo ""
echo "   Demo path: customer-B → Accounts → Break dep-001 → code 123456 → Pay someone new"
echo "              → Safe Account Ltd, EUR 4850.00, SEPA Instant → HOLD + scam warning."
