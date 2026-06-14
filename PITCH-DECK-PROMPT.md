# Prompt to give Claude to generate the PowerPoint deck

Copy everything inside the box below into a fresh Claude chat. It's fully self-contained — Claude needs no files.

---

````text
You are an expert pitch-deck designer. Build me a PowerPoint deck for a 3-MINUTE hackathon pitch
(iFX Hack 2026 — Fintech & Trading, powered by AWS). Deliverable: (1) a slide-by-slide spec
(title, exact on-slide text, layout description, and speaker notes for each slide), AND (2) a
downloadable .pptx file generated with python-pptx implementing the dark theme below. Keep going
until both are produced; if you can run code, generate and give me the .pptx to download.

NON-NEGOTIABLE CONSTRAINTS
- 6 core slides + 3 appendix (Q&A backup) slides. NOT more on the core deck.
- This is a 3-minute pitch where a LIVE PRODUCT DEMO carries the middle. The judges explicitly said
  "a live working demo beats a beautiful slideshow." So slides must be MINIMAL: one idea per slide,
  a big headline, ≤ ~15 words of body, generous negative space. Put the detail in SPEAKER NOTES,
  never on the slide. No bullet walls. No paragraphs on a slide.
- Story-first, honest tone. Judges reward honesty about what's complete vs not.
- PLAIN LANGUAGE on the 6 CORE slides — NO developer jargon. Do NOT put "regression / CI / test-suite
  / 30-of-30 / CloudFormation / IaC / SDK / API gateway" on the core slides. The live demo is the proof
  that it works; don't restate engineering stats. ALL technical proof (test counts, CloudFormation,
  SDK, ML metrics, architecture) lives in the APPENDIX, surfaced only if a judge asks in Q&A.
- 16:9.

JUDGING CRITERIA (design to win these): Does-it-work 40% · Worth-building 35% · Can-you-pitch 15% ·
Clever 10%. Four judges: a CTO (does it really work), an AWS Solutions Architect (cloud story), an
innovation founder (fresh angle / business), and an academic statistician (ML rigour/honesty).

BRAND / VISUAL SYSTEM (dark "fraud control-room" aesthetic — match the product):
- Background #0C1015; panels #141A21 / #1A222C; hairline borders #232B36.
- Text: primary #E6EDF3, secondary #93A1B0, muted #5C6773.
- Brand accent: cyan #4CC2D6 (and #6FD0E0). Outcome accents: ALLOW green #3FB950, CONFIRM amber
  #D29922, HOLD orange #DB6D28, BLOCK red #F85149.
- Fonts: a clean geometric grotesk for headlines (e.g. Space Grotesk / IBM Plex Sans), and a
  monospace (IBM Plex Mono) for any numbers, codes, or "data" accents. Big confident headlines.
- Feel: precise, technical, trustworthy — like a real-time ops console, not a startup pastel deck.
  Use subtle dark gradients/grid texture, a thin cyan→red "severity" accent line as a motif.

WHAT THE PRODUCT IS (all facts below are verified — do not invent or inflate):
Diakrisis is a real-time fraud-DECISION engine for banks. It catches authorised-push-payment (APP)
/ "safe-account" scams — where a victim is socially-engineered into moving THEIR OWN money. These
beat classic per-transaction fraud models because each step looks individually fine. Since the UK
PSR's mandatory APP-fraud reimbursement (Oct 2024), banks are now LEGALLY LIABLE for these losses —
a hard, board-level financial reason to buy. The signature move: Diakrisis scores the SEQUENCE, not
single transactions. It decides in under 50 ms (deterministic path).

THE 6 CORE SLIDES — use this content exactly (refine wording lightly, keep the meaning & the numbers):

SLIDE 1 — TITLE
- On slide: "DIAKRISIS" (large). Tagline: "Stops the scam your bank can't see." Footer: "iFX Hack 2026
  · Fintech & Trading · powered by AWS".
- Visual: near-empty dark canvas; a broken/severed cyan-and-red ring mark; lots of negative space.
- Speaker notes (the hook, do NOT read the slide): "Your mum gets a call — 'we've detected fraud,
  quickly move your savings to a safe account.' She breaks her term deposit and sends €5,000 to the
  'safe' account. It WAS the scam. Every step looked normal to her bank, so it went through. And since
  October 2024, the bank has to refund her — banks are now legally on the hook for the fraud they can't
  see, because their systems score one transaction at a time. The scam is the SEQUENCE."

SLIDE 2 — THE PROBLEM
- On slide headline: "The scam is the sequence." Sub (one line): "'Safe-account' scams trick people
  into moving their OWN money — every step looks normal, so it clears." Callout chip (red): "Since Oct
  2024, banks must REFUND these — liable for the fraud they can't see." (Do NOT put the acronym "APP"
  on the slide; explain "authorised push payment" only in the speaker notes.)
- Visual: a 3-link horizontal chain — "Break deposit" → "Funds freed" → "Sweep to 'safe' account" —
  each link tagged "looks normal ✓" in muted text; the whole chain underlined by the cyan→red severity
  line and tagged "= the scam" in red.
- Speaker notes: state the problem and the liability; "per-transaction models miss it because no single
  step is fraud — the pattern across steps is."

SLIDE 3 — LIVE DEMO (you switch to the real product here)
- On slide: a big "● LIVE" badge (cyan). One line: "Break the deposit → sweep to a 'safe account'."
  Minimal — the real demo is the content.
- Visual: a faint, darkened screenshot-style mock of a phone showing "We've paused this payment to
  protect you" as a background; the LIVE badge on top.
- Speaker notes (the demo script): "I'm the victim. I break the term deposit — step-up, confirmed,
  funds freed. Now I sweep €4,850 to a brand-new 'safe account'. [the pause screen appears] It didn't
  just block a number — it says: 'This looks like an account-liquidation scam — funds were just freed
  and are now going to a brand-new payee — we've paused it to protect you.' It NAMED the scam to her,
  in plain English. No code, no score, one button: Cancel. It linked the deposit-break to the sweep,
  seconds apart, across two separate actions, in under 50 milliseconds."

SLIDE 4 — HOW IT WORKS
- On slide headline: "Two faces. One brain." Two columns: LEFT "CUSTOMER — never sees a score. Just
  plain English that names the scam." RIGHT "ANALYST — sees every signal, the kill-chain timeline, the
  verdict." Bottom band (mono, SHORT): "One decision · fully explainable · under 50 ms." (the
  "deterministic rules + ML + AI, any one can fail, weight-capped spine" detail is SPOKEN, not on the slide)
- Visual: a split panel — left a clean bank "Paused" card, right a dark analyst console (timeline +
  signal bars) — and a row of three small badges "Engine ✓  ML ✓  AI ✓  agree".
- Speaker notes (THE line to say verbatim): "The customer never sees a score; the analyst sees
  everything. And the key engineering choice: any one of the three — or all of the machine-learning —
  can fail, and the engine still makes a fully-explainable, regulator-attributable decision in under
  50 ms, because the deterministic rules engine is the SPINE and the ML and AI are weight-capped
  advisors that can never override it."

SLIDE 5 — WHY IT WINS
- On slide headline: "Explainable. Networked. Honest." Three columns (icon + 1 short line each):
  (1) "REGULATOR-READY — every decision attributable to a named signal. Black-box incumbents can't."
  (2) "NETWORK MOAT — flag a payee on one bank, warn the next. A network, not a feature."
  (3) "AWS-READY — built to deploy on AWS: a step, not a rebuild." (keep the CloudFormation /
      free-tier / SDK specifics for Appendix A2 — NOT on this slide)
- Visual: three equal columns on dark panels, each with a simple line-icon in cyan.
- Speaker notes: explain the moat (cross-account reputation compounds with every bank that joins —
  per-bank incumbents structurally can't copy a network) and be honest that it runs locally with the
  AWS path IaC-ready.

SLIDE 6 — CLOSE / ASK
- On slide: "Real. Today." Then: "You just saw it work — live, not a mockup." Then: "Who pays: the
  fraud chief now personally liable." Footer one-liner (cyan, larger): "Watch the whole sequence. Name
  the scam to the victim. Prove every decision to the regulator."
  (Do NOT put test counts / "30-of-30 regression" on this slide — that is Appendix/Q&A only.)
- Visual: confident, sparse; the one-liner dominant; the cyan→red motif as a base rule.
- Speaker notes: "What's real: it runs end-to-end, live, right now — 30-of-30 regression green, full
  suite passing. We're honest about the rest: it runs on Docker today, the AWS CloudFormation is
  written and the data layer is free-tier-ready — deploying is a step, not a rebuild. And the ML is
  trained on public card-fraud data; the APP-scam intelligence is the explainable rules, deliberately,
  because no labelled APP data exists for anyone. Diakrisis watches the whole sequence, names the scam
  to the victim, and proves every decision to the regulator. That's the layer the incumbents leave on
  the table."

APPENDIX SLIDES (Q&A backup — title them "Appendix", do not count toward the 3 minutes):

A1 — "The model, honestly" (for the academic judge)
- On slide (mono table): M1 = Smile GradientTreeBoost, 300 trees, isotonic-calibrated. Validation
  (time-split of IEEE-CIS): PR-AUC 0.422 · ROC-AUC 0.834 · base rate 3.48% (≈12× lift). Capped at 18
  of ~100 points — it NEVER decides. "Recompute it yourself from the committed val_scores.csv."
- Note: "APP-scam detection is the explainable RULES — no labelled APP dataset exists for anyone; the
  ML adds anomaly-shape, weight-capped, as a second opinion."

A2 — "AWS in one slide" (for the AWS judge)
- On slide: "Today: AWS SDK v2 DynamoDB enhanced client; the 72-hour kill-chain decay IS a DynamoDB
  TTL attribute (enabled). Local→AWS is a ~10-line client change (conditional endpoint + IAM creds).
  Fargate / OpenSearch k-NN / Bedrock co-judge all sit behind interfaces that already exist."
- Visual: a small AWS architecture strip (API Gateway → Fargate services → DynamoDB + OpenSearch +
  Bedrock), all native icons.

A3 — "Architecture"
- On slide: a clean diagram — customer bank + analyst console → API gateway → decision-service
  (deterministic engine + M1 ML + M2 k-NN + Gemma co-judge) → DynamoDB. Label the <50 ms deterministic
  path and the bounded, advisory co-judge outside it.

OUTPUT INSTRUCTIONS
1) First give me the full slide-by-slide spec (per slide: TITLE, ON-SLIDE TEXT, LAYOUT, SPEAKER NOTES).
2) Then generate a real .pptx with python-pptx: 16:9, the dark theme/colors/fonts above, the speaker
   notes attached to each slide's notes pane, minimal on-slide text, and the cyan→red severity motif.
   Give me the file to download. Use placeholder rectangles/labels where I'd drop in the live
   screenshots (slides 3 and 4) and clearly mark them "REPLACE WITH SCREENSHOT".
3) Keep on-slide text minimal and the design restrained and technical — this supports a live demo, it
   does not replace it.
````

---

*Tip: when Claude returns the `.pptx`, drop in two real screenshots from the live demo — the customer
"We've paused this payment to protect you" screen (slide 3/4 left) and the analyst kill-chain decision
detail (slide 4 right). Those two images do more than any slide text.*
