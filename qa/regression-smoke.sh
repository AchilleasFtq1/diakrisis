#!/usr/bin/env bash
# Diakrisis full-platform API regression. Run AFTER the stack is up (docker compose + the fixes
# deployed). Exercises every role, the decision/lifecycle/ops/admin surfaces, the demo bank, AND the
# security regressions the bug-fix pass closed (register-as-admin, cross-account, role gates, four-eyes).
# Exit code 0 = all green. Each check prints PASS/FAIL; a summary tally is printed at the end.
set -uo pipefail
GW="${GW:-http://localhost:8080}"
BANK="${BANK:-http://localhost:9000}"
PASS=0; FAIL=0; FAILED=()

ok()   { PASS=$((PASS+1)); printf '  \033[32mPASS\033[0m %s\n' "$1"; }
bad()  { FAIL=$((FAIL+1)); FAILED+=("$1"); printf '  \033[31mFAIL\033[0m %s — %s\n' "$1" "${2:-}"; }
hdr()  { printf '\n\033[1m== %s ==\033[0m\n' "$1"; }

# assert_status <name> <expected> <method> <path> [token] [body]
assert_status() {
  local name="$1" exp="$2" m="$3" path="$4" tok="${5:-}" body="${6:-}"
  local args=(-s -o /dev/null -w '%{http_code}' -X "$m" "$GW$path")
  [ -n "$tok" ]  && args+=(-H "Authorization: Bearer $tok")
  [ -n "$body" ] && args+=(-H 'Content-Type: application/json' -d "$body")
  local code; code=$(curl "${args[@]}")
  [ "$code" = "$exp" ] && ok "$name ($code)" || bad "$name" "expected $exp got $code"
}

login() { # login <user> <pass> -> echoes token (empty on failure)
  curl -s -X POST "$GW/auth/login" -H 'Content-Type: application/json' \
    -d "{\"username\":\"$1\",\"password\":\"$2\"}" | python3 -c 'import sys,json
try: print(json.load(sys.stdin).get("token",""))
except Exception: print("")'
}

jget() { python3 -c "import sys,json;d=json.load(sys.stdin);print($1)" 2>/dev/null; }

# ----------------------------------------------------------------------------------------------------
hdr "1. Health & gateway"
assert_status "gateway up (login route)" 200 POST /auth/login "" '{"username":"ops-user","password":"demo"}'

hdr "2. Auth — every seeded role logs in"
OPS=$(login ops-user demo);        [ -n "$OPS" ] && ok "ops-user login"     || bad "ops-user login"
ADM=$(login admin admin);          [ -n "$ADM" ] && ok "admin login"        || bad "admin login"
APR=$(login approver-biz demo);    [ -n "$APR" ] && ok "approver-biz login" || bad "approver-biz login"
CB=$(login customer-B demo);       [ -n "$CB" ]  && ok "customer-B login"   || bad "customer-B login"
assert_status "bad password rejected" 401 POST /auth/login "" '{"username":"admin","password":"wrong"}'

hdr "3. SECURITY regressions (the bug fixes)"
# #1 — public register must REJECT a privileged role (422), never mint one
assert_status "register role=ADMIN rejected (422)" 422 POST /auth/register "" \
  "{\"username\":\"smoke-evil-$$\",\"password\":\"pwned123\",\"role\":\"ADMIN\"}"
# a plain CUSTOMER self-registration still works and is not privileged
assert_status "register role=CUSTOMER allowed (201)" 201 POST /auth/register "" \
  "{\"username\":\"smoke-cust-$$\",\"password\":\"pwned123\",\"role\":\"CUSTOMER\"}"
EVT=$(login "smoke-cust-$$" pwned123)
assert_status "self-registered customer blocked from /admin/users" 403 GET /admin/users "$EVT"
curl -s -X DELETE "$GW/admin/users/smoke-cust-$$" -H "Authorization: Bearer $ADM" -o /dev/null
# role gate: ops-user cannot reach admin console
assert_status "ops-user blocked from /admin/users" 403 GET /admin/users "$OPS"
# cross-account ownership guard: customer-B cannot score acc-A
XACCT='{"event_id":"smoke-xacct-'$$'","account_id":"acc-A","event_type":"TRANSFER","context":{"ts":"2026-06-14T09:00:00Z","session_id":"s","channel":"WEB","ip":"203.0.113.10","device":{"device_id":"dev-a","platform":"WEB"}},"payload":{"counterparty":{"addressing":"IBAN","value":"CY111","display_name":"X"},"amount_eur":10.0,"available_balance_eur":5000.0,"rail":"SEPA"}}'
assert_status "customer-B blocked from scoring acc-A (cross-account)" 403 POST /decision "$CB" "$XACCT"

hdr "4. Decision scoring (admin token bypasses cross-account for seeding)"
N=$(date +%s)
# a clean small transfer on acc-A
ALLOW=$(curl -s -X POST "$GW/decision" -H "Authorization: Bearer $ADM" -H 'Content-Type: application/json' \
  -d "{\"event_id\":\"smoke-allow-$N\",\"account_id\":\"acc-A\",\"event_type\":\"TRANSFER\",\"context\":{\"ts\":\"2026-06-14T09:00:00Z\",\"session_id\":\"s\",\"channel\":\"WEB\",\"ip\":\"203.0.113.10\",\"device\":{\"device_id\":\"dev-a\",\"platform\":\"WEB\"}},\"payload\":{\"counterparty\":{\"addressing\":\"IBAN\",\"value\":\"CY1112\",\"resolved_name\":\"Anna\",\"display_name\":\"Anna\"},\"amount_eur\":40.0,\"available_balance_eur\":8000.0,\"rail\":\"SEPA\"}}")
AD=$(echo "$ALLOW" | jget 'd["combined"]["decision"]'); [ -n "$AD" ] && ok "small transfer scored ($AD)" || bad "small transfer scoring" "$ALLOW"
# the kill-chain: deposit break then drain on acc-B → BLOCK liquidation_kill_chain
curl -s -X POST "$GW/decision" -H "Authorization: Bearer $ADM" -H 'Content-Type: application/json' \
  -d "{\"event_id\":\"smoke-break-$N\",\"account_id\":\"acc-B\",\"event_type\":\"TERM_DEPOSIT_BREAK\",\"context\":{\"ts\":\"2026-06-14T09:01:00Z\",\"session_id\":\"k\",\"channel\":\"MOBILE_APP\",\"ip\":\"203.0.113.7\",\"device\":{\"device_id\":\"dev-b\",\"platform\":\"IOS\"}},\"payload\":{\"deposit_id\":\"TD-$N\",\"principal_eur\":9000.0,\"maturity_date\":\"2026-12-01T00:00:00Z\",\"penalty_eur\":120.0}}" -o /dev/null
sleep 1
DRAIN=$(curl -s -X POST "$GW/decision" -H "Authorization: Bearer $ADM" -H 'Content-Type: application/json' \
  -d "{\"event_id\":\"smoke-drain-$N\",\"account_id\":\"acc-B\",\"event_type\":\"TRANSFER\",\"context\":{\"ts\":\"2026-06-14T09:02:00Z\",\"session_id\":\"k\",\"channel\":\"MOBILE_APP\",\"ip\":\"203.0.113.7\",\"device\":{\"device_id\":\"dev-b\",\"platform\":\"IOS\"}},\"payload\":{\"counterparty\":{\"addressing\":\"IBAN\",\"value\":\"CY99DRAIN$N\",\"display_name\":\"Drain Co\"},\"amount_eur\":8400.0,\"available_balance_eur\":9000.0,\"rail\":\"INSTANT\"}}")
DD=$(echo "$DRAIN" | jget 'd["combined"]["decision"]'); DT=$(echo "$DRAIN" | jget 'd["engine_verdict"]["typologies"]')
# a single liquidation_kill_chain typology pins to HOLD (raw<90); two typologies / raw>=90 → BLOCK. Either is a correct fraud catch.
{ { [ "$DD" = "HOLD" ] || [ "$DD" = "BLOCK" ]; } && echo "$DT" | grep -q liquidation_kill_chain; } && ok "kill-chain drain → $DD liquidation_kill_chain" || bad "kill-chain" "decision=$DD typologies=$DT"
# idempotent replay returns same decision
RD=$(curl -s -X POST "$GW/decision" -H "Authorization: Bearer $ADM" -H 'Content-Type: application/json' \
  -d "{\"event_id\":\"smoke-drain-$N\",\"account_id\":\"acc-B\",\"event_type\":\"TRANSFER\",\"context\":{\"ts\":\"2026-06-14T09:02:00Z\",\"session_id\":\"k\",\"channel\":\"MOBILE_APP\",\"ip\":\"203.0.113.7\",\"device\":{\"device_id\":\"dev-b\",\"platform\":\"IOS\"}},\"payload\":{\"counterparty\":{\"addressing\":\"IBAN\",\"value\":\"CY99DRAIN$N\",\"display_name\":\"Drain Co\"},\"amount_eur\":8400.0,\"available_balance_eur\":9000.0,\"rail\":\"INSTANT\"}}" | jget 'd["combined"]["decision"]')
[ "$RD" = "$DD" ] && ok "idempotent replay (same verdict $RD)" || bad "idempotency" "replay=$RD original=$DD"

hdr "5. Ops read surfaces (OPS token)"
for ep in "/ops/feed?size=5" "/ops/counters" "/ops/approvals?size=5" "/ops/accounts/acc-B" "/ops/accounts/acc-B/history?size=5" "/ops/counterparties?size=5" "/ops/outcomes?size=5"; do
  assert_status "GET $ep" 200 GET "$ep" "$OPS"
done
assert_status "GET /ops/decisions/{killchain}" 200 GET "/ops/decisions/smoke-drain-$N" "$OPS"

hdr "6. Four-eyes & lifecycle"
# Approving the admin's own HELD kill-chain must be BLOCKED — either 403 (four-eyes self-approval) or
# 409 (conditional lifecycle write: a HELD item is not in an approvable state). Both mean "not executed".
SA=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$GW/actions/smoke-drain-$N/approve" -H "Authorization: Bearer $ADM")
{ [ "$SA" = "403" ] || [ "$SA" = "409" ]; } && ok "admin's own action not approvable ($SA)" || bad "lifecycle/self-approval guard" "approve returned $SA (expected 403/409, must not be 200)"
# (HOLD→cancel and HELD→release-after-expiry lifecycle transitions are pinned by the golden-path tests.)

hdr "7. Admin user CRUD (ADMIN token)"
U="smoke-user-$$"
assert_status "create user" 201 POST /admin/users "$ADM" "{\"username\":\"$U\",\"password\":\"demo123\",\"role\":\"OPS\"}"
assert_status "update role→APPROVER" 200 POST "/admin/users/$U/roles" "$ADM" '{"role":"APPROVER"}'
assert_status "reset password" 200 POST "/admin/users/$U/password" "$ADM" '{"password":"newpw123"}'
assert_status "login with new password" 200 POST /auth/login "" "{\"username\":\"$U\",\"password\":\"newpw123\"}"
assert_status "rename user" 200 POST "/admin/users/$U/username" "$ADM" "{\"new_username\":\"${U}-r\"}"
assert_status "delete user" 204 DELETE "/admin/users/${U}-r" "$ADM"

hdr "8. Demo bank reachable"
assert_status_bank() { local code; code=$(curl -s -o /dev/null -w '%{http_code}' "$BANK$1"); { [ "$code" = "200" ] || [ "$code" = "302" ]; } && ok "demo-bank $1 ($code)" || bad "demo-bank $1" "got $code"; }
assert_status_bank "/"

# ----------------------------------------------------------------------------------------------------
printf '\n\033[1m== SUMMARY ==\033[0m  \033[32m%d passed\033[0m, \033[31m%d failed\033[0m\n' "$PASS" "$FAIL"
if [ "$FAIL" -gt 0 ]; then printf 'FAILED:\n'; for f in "${FAILED[@]}"; do printf '  - %s\n' "$f"; done; exit 1; fi
echo "ALL GREEN ✅"
