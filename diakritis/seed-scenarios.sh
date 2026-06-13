#!/usr/bin/env bash
# Re-drive the demo scenarios through the live gateway so every decision is scored by the real engine
# AND carries full per-event SessionContext (device / IP / geo / channel / beneficiary). Uses the admin
# token, which is not a CUSTOMER and therefore bypasses the cross-account ownership guard, so one token
# can seed every demo account. Idempotent ids are nonce-suffixed, so re-running adds a fresh clean set.
set -euo pipefail
GW="${GW:-http://localhost:8080}"
NONCE="$(date +%s)"
TOKEN="$(curl -s -X POST "$GW/auth/login" -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"admin"}' | python3 -c 'import sys,json;print(json.load(sys.stdin)["token"])')"

post() { # post <json>
  curl -s -X POST "$GW/decision" -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d "$1" \
    | python3 -c "import sys,json;d=json.load(sys.stdin);print('  %16s  score=%s  typ=%s' % (d.get('combined',{}).get('decision'), d.get('engine_verdict',{}).get('score'), d.get('engine_verdict',{}).get('typologies')))"
}

ctx() { # ctx <session> <channel> <ip> <device> <platform>
  printf '{"ts":"2026-06-13T22:%02d:00Z","session_id":"%s","channel":"%s","ip":"%s","device":{"device_id":"%s","platform":"%s"}}' \
    "$((RANDOM % 60))" "$1" "$2" "$3" "$4" "$5"
}

# Canonical accounts (acc-A / acc-B) carry the seeded baselines + posture, so the engine's typologies
# (e.g. liquidation_kill_chain) fire on them. The safe-account and payroll runs use their own accounts.
A="acc-A"; KC="acc-B"; S="democtx-S-$NONCE"; BIZ="democtx-BIZ-$NONCE"

echo "1) clean ALLOW — small familiar transfer (CY/WEB/known device)"
post "{\"event_id\":\"t-allow-$NONCE\",\"account_id\":\"$A\",\"event_type\":\"TRANSFER\",\"context\":$(ctx sA WEB 203.0.113.10 dev-a WEB),\"payload\":{\"counterparty\":{\"addressing\":\"IBAN\",\"value\":\"CY17002001280000001200111111\",\"resolved_name\":\"Anna Demetriou\",\"display_name\":\"Anna Demetriou\"},\"amount_eur\":45.00,\"available_balance_eur\":8000.00,\"rail\":\"SEPA\"}}"

echo "2) CONFIRM — larger mobile transfer"
post "{\"event_id\":\"t-confirm-$NONCE\",\"account_id\":\"$A\",\"event_type\":\"TRANSFER\",\"context\":$(ctx sA MOBILE_APP 203.0.113.10 dev-a IOS),\"payload\":{\"counterparty\":{\"addressing\":\"IBAN\",\"value\":\"CY17002001280000001200222222\",\"display_name\":\"Marble & Co\"},\"amount_eur\":850.00,\"available_balance_eur\":8000.00,\"rail\":\"SEPA\"}}"

echo "3) kill-chain step 1 — break term deposit on acc-B (frees funds)"
post "{\"event_id\":\"t-kc-break-$NONCE\",\"account_id\":\"$KC\",\"event_type\":\"TERM_DEPOSIT_BREAK\",\"context\":$(ctx sKC MOBILE_APP 203.0.113.7 dev-b IOS),\"payload\":{\"deposit_id\":\"TD-$NONCE\",\"principal_eur\":9000.00,\"maturity_date\":\"2026-12-01T00:00:00Z\",\"penalty_eur\":120.00}}"
sleep 1

echo "4) kill-chain step 2 — drain to a brand-new payee (BLOCK · liquidation_kill_chain)"
post "{\"event_id\":\"t-kc-drain-$NONCE\",\"account_id\":\"$KC\",\"event_type\":\"TRANSFER\",\"context\":$(ctx sKC MOBILE_APP 203.0.113.7 dev-b IOS),\"payload\":{\"counterparty\":{\"addressing\":\"IBAN\",\"value\":\"CY99RAPID0000000000000099999\",\"display_name\":\"Rapid Holdings Ltd\"},\"amount_eur\":8400.00,\"available_balance_eur\":9000.00,\"rail\":\"INSTANT\"}}"

echo "5) geo anomaly — transfer from a foreign network (JO) on a new device"
post "{\"event_id\":\"t-geo-$NONCE\",\"account_id\":\"$A\",\"event_type\":\"TRANSFER\",\"context\":$(ctx sX MOBILE_APP 198.51.100.9 dev-x ANDROID),\"payload\":{\"counterparty\":{\"addressing\":\"IBAN\",\"value\":\"CY17002001280000001200333333\",\"display_name\":\"Overseas Pay\"},\"amount_eur\":1500.00,\"available_balance_eur\":8000.00,\"rail\":\"SEPA\"}}"

echo "6) safe-account scam — large move to a 'safe' account"
post "{\"event_id\":\"t-safe-$NONCE\",\"account_id\":\"$S\",\"event_type\":\"TRANSFER\",\"context\":$(ctx sS MOBILE_APP 203.0.113.21 dev-s IOS),\"payload\":{\"counterparty\":{\"addressing\":\"IBAN\",\"value\":\"CY55SAFE0000000000000055555\",\"display_name\":\"Safe Holding Account\"},\"amount_eur\":6000.00,\"available_balance_eur\":7000.00,\"rail\":\"INSTANT\"}}"

echo "7) mass payment — payroll batch (REQUIRE_APPROVAL · per-line breakdown)"
post "{\"event_id\":\"t-mass-$NONCE\",\"account_id\":\"$BIZ\",\"event_type\":\"MASS_PAYMENT\",\"context\":$(ctx sBIZ WEB 203.0.113.40 dev-biz WEB),\"payload\":{\"batch_id\":\"PR-$NONCE\",\"purpose_hint\":\"June payroll\",\"items\":[{\"item_id\":\"L1\",\"counterparty\":{\"addressing\":\"IBAN\",\"value\":\"CY17002001280000001200440001\",\"display_name\":\"Staff 1\"},\"amount_eur\":2200.00},{\"item_id\":\"L2\",\"counterparty\":{\"addressing\":\"IBAN\",\"value\":\"CY17002001280000001200440002\",\"display_name\":\"Staff 2\"},\"amount_eur\":2600.00},{\"item_id\":\"L3\",\"counterparty\":{\"addressing\":\"IBAN\",\"value\":\"CY99RAPID0000000000000099999\",\"display_name\":\"Rapid Holdings Ltd\"},\"amount_eur\":18000.00}],\"total_eur\":22800.00,\"available_balance_eur\":40000.00,\"rail\":\"SEPA\"}}"

echo "done — nonce $NONCE"
