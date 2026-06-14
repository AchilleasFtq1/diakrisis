#!/usr/bin/env python3
# Populate the fraud-ops analyst feed with a DIVERSE, transaction-heavy, realistic set of decisions so
# the Overview looks like a real ops console — varied accounts / types / amounts / outcomes — AND so the
# "money protected" / wins-board figures are non-zero (we cancel the high-value HOLDs, which records a
# CONFIRMED_SAVE for each — exactly what a customer abandoning a paused scam payment does).
#
# SAFETY: drives ONLY throwaway account ids via an ADMIN token. Per-account posture/observations/
# baselines are keyed by account_id, so this CANNOT touch acc-A / acc-B / acc-C (the live-demo
# accounts). The throwaway transfers/kill-chains also warm the JVM JIT on the decision hot path.
#
# Tuning runs: set SEED_SUFFIX=-t1 to use fresh, isolated account ids (acc-4471-t1, …) so re-runs do
# not compound posture. prep-demo.sh runs it with NO suffix on a freshly-reset stack → clean names.
import json, os, sys, time, urllib.request, urllib.error
from collections import Counter

GW = os.environ.get("GW", "http://localhost:8080")
TS = "2026-06-14T10:00:00Z"
RUN = str(int(time.time()))[-5:]
SUF = os.environ.get("SEED_SUFFIX", "")


def login():
    req = urllib.request.Request(GW + "/auth/login",
        data=json.dumps({"username": "admin", "password": "admin"}).encode(),
        headers={"Content-Type": "application/json"})
    return json.load(urllib.request.urlopen(req, timeout=15))["token"]


def post(tok, ev):
    req = urllib.request.Request(GW + "/decision", data=json.dumps(ev).encode(),
        headers={"Content-Type": "application/json", "Authorization": "Bearer " + tok}, method="POST")
    try:
        d = json.load(urllib.request.urlopen(req, timeout=30))
        v = d.get("engine_verdict", {})
        decision = d.get("combined", {}).get("decision") or v.get("decision")
        return decision, v.get("score"), v.get("typologies"), d.get("latency_ms")
    except urllib.error.HTTPError as e:
        return "HTTP%s" % e.code, None, e.read().decode()[:90], None
    except Exception as e:
        return "ERR", None, str(e)[:90], None


def cancel(tok, event_id):
    """Customer abandons a paused (HELD) payment → records a CONFIRMED_SAVE (money protected)."""
    req = urllib.request.Request(GW + "/actions/" + urllib.parse.quote(event_id) + "/cancel",
        data=b"", headers={"Authorization": "Bearer " + tok}, method="POST")
    try:
        urllib.request.urlopen(req, timeout=15).read()
        return True
    except Exception:
        return False


def acct(base):
    return base + SUF


def ctx(sid, ch="MOBILE_APP", ip="203.0.113.7", dev="dev-home", plat="IOS"):
    return {"ts": TS, "session_id": sid, "channel": ch, "ip": ip, "device": {"device_id": dev, "platform": plat}}


def cp(value, name, created=None, addr="IBAN"):
    return {"addressing": addr, "value": value, "resolved_name": name, "display_name": name,
            "beneficiary_created_at": created}


def E(base, etype, payload, **ck):
    a = acct(base)
    eid = "%s-%s-%s" % (a, etype.lower(), RUN)
    return {"event_id": eid, "account_id": a, "event_type": etype, "payload": payload,
            "context": ctx(eid, **ck)}


def brk(base, principal, penalty=120.0):
    return E(base, "TERM_DEPOSIT_BREAK", {"deposit_id": "TD-%s" % base, "principal_eur": principal,
        "maturity_date": "2026-12-01T00:00:00Z", "penalty_eur": penalty})


def xfer(base, amount, bal, value, name, rail="SEPA", created=None, **ck):
    return E(base, "TRANSFER", {"counterparty": cp(value, name, created), "amount_eur": amount,
        "available_balance_eur": bal, "rail": rail}, **ck)


def p2p(base, amount, bal, alias, name):
    return E(base, "P2P_TRANSFER", {"counterparty": cp(alias, name, addr="MSISDN"),
        "amount_eur": amount, "available_balance_eur": bal, "rail": "P2P"})


def benadd(base, value, name):
    return E(base, "BENEFICIARY_ADD", {"counterparty": cp(value, name)})


def limit(base, cur, new):
    return E(base, "LIMIT_CHANGE", {"current_limit_eur": cur, "new_limit_eur": new},
        ch="WEB", dev="dev-office", plat="WEB")


# ── the diverse, transaction-heavy decision set ─────────────────────────────────────────────────
# Each entry: (event, cancel?)  — cancel=True means "customer abandons this paused payment" → CONFIRMED_SAVE.
# Only HOLD (HELD) actions are cancellable-as-a-save; cancel flags on non-HOLD rows are ignored.
def deck():
    return [
        # ── standalone term-deposit breaks → CONFIRM (step-up) ──────────────────────────────────
        (brk("acc-4471", 12000.0), False),
        (brk("acc-2261", 4200.0), False),
        (brk("acc-9015", 3300.0), False),

        # ── liquidation kill-chains → BLOCK (full sweep to a brand-new payee, fresh device) ─────
        (brk("acc-3318", 26000.0), False),
        (xfer("acc-3318", 24800.0, 25000.0, "CY99NEW3318", "Offshore Nominee", rail="INSTANT",
              created=TS, ip="198.51.100.7", dev="dev-unknown-3318", plat="WEB"), False),
        (brk("acc-7741", 9000.0), False),
        (xfer("acc-7741", 8700.0, 8800.0, "CY99NEW7741", "Sterling Holdings", rail="INSTANT", created=TS), False),
        (brk("acc-8823", 5500.0), False),
        (xfer("acc-8823", 5300.0, 5400.0, "CY99NEW8823", "QuickPay Ltd", rail="INSTANT", created=TS), False),

        # ── high-value paused transfers → HOLD (instant rail to a brand-new payee; cancelled = saved) ──
        (xfer("acc-1190", 14500.0, 16000.0, "CY44NEW1190", "Meridian Build Ltd", rail="INSTANT"), True),
        (xfer("biz-7180", 22000.0, 24000.0, "CY44NEW7180", "Apex Trade Co", rail="INSTANT"), True),
        (xfer("acc-4402", 8500.0, 9000.0, "CY44NEW4402", "Aegean Transfers", rail="INSTANT"), True),
        (xfer("acc-5560", 6200.0, 6800.0, "CY44NEW5560", "Nicosia Property LLC", rail="INSTANT"), True),

        # ── purchase-scam paused payments → HOLD (modest, irrevocable rail, brand-new payee) ────
        (xfer("acc-8044", 1850.0, 9000.0, "CY77NEW8044", "MarketPlace Seller", rail="INSTANT", created=TS), True),
        (p2p("acc-6627", 1500.0, 22000.0, "+35799112233", "George P."), True),
        (p2p("acc-3390", 95.0, 4200.0, "+35799887766", "Andreas K."), False),

        # ── modest everyday transfers → CONFIRM (the routine volume) ────────────────────────────
        (xfer("acc-2204", 640.0, 9200.0, "CY55PAY2204", "Meridian Telecom"), False),
        (xfer("acc-5571", 780.0, 5100.0, "CY55PAY5571", "EAC Utility"), False),
        (xfer("acc-9942", 1250.0, 7400.0, "CY55PAY9942", "CYTA Mobile"), False),
        (xfer("acc-6680", 410.0, 3300.0, "CY55PAY6680", "Public Co"), False),

        # ── beneficiary adds → CONFIRM ──────────────────────────────────────────────────────────
        (benadd("acc-7705", "CY77ADD7705", "New Supplier Co"), False),
        (benadd("acc-1175", "CY77ADD1175", "Olympia Imports"), False),

        # ── limit raises > 2x → REQUIRE_APPROVAL (four-eyes) ────────────────────────────────────
        (limit("biz-5512", 10000.0, 30000.0), False),
        (limit("biz-7180b", 50000.0, 150000.0), False),
    ]


def main():
    tok = login()
    rows = []
    for ev, want_cancel in deck():
        verdict, score, typ, lat = post(tok, ev)
        rows.append({"acct": ev["account_id"], "type": ev["event_type"], "eid": ev["event_id"],
                     "verdict": verdict, "score": score,
                     "typ": ",".join(typ) if isinstance(typ, list) else (typ or ""),
                     "lat": lat, "amount": ev["payload"].get("amount_eur"), "cancel": want_cancel})
        time.sleep(0.2)

    # Cancel the flagged HOLDs → each records a CONFIRMED_SAVE for its amount (money protected).
    saved_cents, saves = 0, 0
    for r in rows:
        if r["cancel"] and r["verdict"] == "HOLD":
            if cancel(tok, r["eid"]):
                r["state"] = "CANCELLED→SAVE"
                saves += 1
                saved_cents += int(round((r["amount"] or 0) * 100))
        elif r["verdict"] == "HOLD":
            r["state"] = "HELD (pending)"

    print("\n  %-13s %-18s %-16s %-6s %-7s %-15s %s" %
          ("ACCOUNT", "TYPE", "OUTCOME", "SCORE", "LAT", "LIFECYCLE", "TYPOLOGIES/NOTE"))
    print("  " + "-" * 110)
    spread = Counter()
    for r in rows:
        spread[r["verdict"]] += 1
        print("  %-13s %-18s %-16s %-6s %-7s %-15s %s" % (
            r["acct"], r["type"], r["verdict"],
            r["score"] if r["score"] is not None else "-",
            (str(r["lat"]) + "ms") if r["lat"] is not None else "-",
            r.get("state", ""), r["typ"][:50]))
    print("\n  SPREAD:", dict(spread))
    print("  MONEY PROTECTED: EUR {:,.2f}  across {} confirmed saves".format(saved_cents / 100.0, saves))


if __name__ == "__main__":
    main()
