// The Diakrisis signal catalog (SDD §6) — verified against the engine's live Weights.java. This is the
// authoritative glossary the Reference page renders. Weights are the real values the engine sums.

export interface SignalDoc {
  id: string;
  name: string;
  weight: number;
  cap?: boolean;       // M1/M2 contributions are capped
  firesOn: string;
  meaning: string;
}

export interface SignalFamily {
  prefix: string;
  label: string;
  hex: string;
  blurb: string;
  signals: SignalDoc[];
}

export const SIGNAL_FAMILIES: SignalFamily[] = [
  {
    prefix: 'B',
    label: 'Beneficiary / counterparty',
    hex: '#4cc2d6',
    blurb: 'Who is being paid, and our history with that resolved identity.',
    signals: [
      { id: 'B1', name: 'New counterparty', weight: 14, firesOn: 'transfers · lines', meaning: 'No payment history on this resolved identity — a payee we have never seen.' },
      { id: 'B2', name: 'Counterparty age (decay)', weight: 10, firesOn: 'transfers', meaning: 'How new the payee is: ≈1.0 when minutes old, ≈0.9 next day, fading to 0 over ~60 days.' },
      { id: 'B3', name: 'Added this session', weight: 8, firesOn: 'transfers', meaning: 'The payee was created in the current login session — added then paid in one sitting.' },
      { id: 'B4', name: 'History depth', weight: -12, firesOn: 'transfers', meaning: 'Protective (negative): a deep, established payment history credits trust and lowers the score.' },
      { id: 'B5', name: 'Name mismatch (CoP)', weight: 16, firesOn: 'transfers · adds · lines', meaning: 'Confirmation-of-Payee: the resolved account name does not match the expected name.' },
    ],
  },
  {
    prefix: 'P',
    label: 'P2P alias',
    hex: '#a371f7',
    blurb: 'Phone / email addressing and re-point detection (the SIM-swap tell).',
    signals: [
      { id: 'P1', name: 'Alias re-point', weight: 22, firesOn: 'P2P', meaning: 'A phone/email alias now resolves to a different account than its own history — the SIM-swap / hijack signature. Fires on any amount.' },
    ],
  },
  {
    prefix: 'A',
    label: 'Amount behaviour',
    hex: '#d29922',
    blurb: 'How the amount compares to what is normal for this account and payee.',
    signals: [
      { id: 'A1', name: 'Amount anomaly', weight: 12, firesOn: 'transfers', meaning: 'The salami-aware "logical amount" is a statistical outlier vs this account’s own distribution.' },
      { id: 'A2', name: 'Balance drain', weight: 18, firesOn: 'transfers', meaning: 'Fraction of available balance being sent — above 0.8 is the classic account-drain tell.' },
      { id: 'A3', name: 'Counterparty-amount anomaly', weight: 12, firesOn: 'transfers · lines', meaning: 'The amount is unusual for THIS specific counterparty’s mean.' },
      { id: 'A4', name: 'Threshold hugging', weight: 6, firesOn: 'transfers', meaning: 'The amount sits just under a round reporting / limit threshold.' },
    ],
  },
  {
    prefix: 'V',
    label: 'Velocity',
    hex: '#db6d28',
    blurb: 'Speed and escalation of activity.',
    signals: [
      { id: 'V1', name: 'Burst velocity', weight: 8, firesOn: 'all', meaning: 'Actions per hour vs the account’s runtime baseline — a sudden burst of activity.' },
      { id: 'V2', name: 'Escalation', weight: 10, firesOn: 'transfers', meaning: 'Rising amounts to the same counterparty over time (grooming / escalation).' },
    ],
  },
  {
    prefix: 'C',
    label: 'Channel & time',
    hex: '#6fd0e0',
    blurb: 'When and how the action arrives.',
    signals: [
      { id: 'C1', name: 'Out-of-pattern time', weight: 6, firesOn: 'all', meaning: 'Off the account’s habit — weekday pattern from the Berka baseline, hour-of-day learned at runtime.' },
      { id: 'C3', name: 'Retry pressure', weight: 8, firesOn: 'transfers', meaning: 'Repeated raised-amount attempts within the session — pushing to get it through.' },
    ],
  },
  {
    prefix: 'G',
    label: 'Geo',
    hex: '#3fb950',
    blurb: 'Where the action comes from (IP country / network).',
    signals: [
      { id: 'G1', name: 'Unfamiliar geo', weight: 12, firesOn: 'all', meaning: 'The IP’s country / CIDR is one this account has not been seen from (vs the observation store).' },
      { id: 'G2', name: 'New network', weight: 6, firesOn: 'all', meaning: 'A new network prefix, but in a familiar country.' },
    ],
  },
  {
    prefix: 'D',
    label: 'Device',
    hex: '#93a1b0',
    blurb: 'The device and platform used.',
    signals: [
      { id: 'D1', name: 'Device age (decay)', weight: 10, firesOn: 'all', meaning: 'How new the device is (first sighting in the observation store) — risky when fresh, decaying over ~21 days; warm-up ramp at cold start.' },
      { id: 'D2', name: 'Platform anomaly', weight: 6, firesOn: 'all', meaning: 'A platform switch the account does not make — e.g. an iOS/Android-only user suddenly on WEB.' },
    ],
  },
  {
    prefix: 'K',
    label: 'Kill-chain posture (72h)',
    hex: '#f85149',
    blurb: 'What the account did in the last 72 hours — the sequence the engine remembers.',
    signals: [
      { id: 'K1', name: 'Funds freed recently', weight: 16, firesOn: 'transfers · batches', meaning: 'A term deposit was broken / funds freed in the last 72h that cover this amount — the liquidation linkage. 72h decay.' },
      { id: 'K2', name: 'Limit raised recently', weight: 10, firesOn: 'transfers', meaning: 'A daily / transfer limit was raised within 72h, scaled by the size of the raise.' },
      { id: 'K3', name: 'Beneficiary-add burst', weight: 8, firesOn: 'transfers', meaning: 'Two or more new payees added in the last 72h.' },
    ],
  },
  {
    prefix: 'MP',
    label: 'Mass payment (batch)',
    hex: '#a371f7',
    blurb: 'Batch-level signals for bulk files (payroll, supplier runs).',
    signals: [
      { id: 'MP1', name: 'New-counterparty share', weight: 16, firesOn: 'batches', meaning: 'Fraction of batch lines paying a brand-new counterparty (payroll ≈ 0; mule fan-out ≈ 1).' },
      { id: 'MP2', name: 'Cadence / total anomaly', weight: 12, firesOn: 'batches', meaning: 'Batch total + day-of-month vs the account’s batch history (payroll is rhythmic).' },
      { id: 'MP4', name: 'Batch drain', weight: 14, firesOn: 'batches', meaning: 'Batch total vs available balance — interacts with K1.' },
    ],
  },
  {
    prefix: 'M',
    label: 'ML models',
    hex: '#4cc2d6',
    blurb: 'The supervised model and the vector-similarity score. Both are capped and additive — weight→0 leaves the engine fully functional.',
    signals: [
      { id: 'M1', name: 'Model score', weight: 18, cap: true, firesOn: 'transfers · lines', meaning: 'Calibrated Smile GradientTreeBoost fraud probability (isotonic-calibrated). Contribution capped.' },
      { id: 'M2', name: 'Fraud-neighbour share', weight: 12, cap: true, firesOn: 'transfers · lines', meaning: 'Distance-weighted share of fraud among the k nearest exemplars (Qdrant k-NN, k≈25). Contribution capped.' },
    ],
  },
  {
    prefix: 'X',
    label: 'Cross-account (network moat)',
    hex: '#f85149',
    blurb: 'The signal no single-account tool can produce — the cross-account view.',
    signals: [
      { id: 'X1', name: 'Cross-account reputation', weight: 20, firesOn: 'transfers · P2P · lines', meaning: 'The destination counterparty was flagged (HELD / BLOCKED / cancelled-hold) on ANOTHER account recently — warns the next victim. 6h half-life decay.' },
    ],
  },
];

export interface OutcomeDoc {
  outcome: string;
  band: string;
  friction: string;
  hex: string;
}

export const OUTCOME_BANDS: OutcomeDoc[] = [
  { outcome: 'ALLOW', band: '0–29', friction: 'None — SCA-exempt where regulation permits (PSD2 RTS Art. 18 TRA).', hex: '#3fb950' },
  { outcome: 'CONFIRM', band: '30–59', friction: 'One specific sentence + one explicit tap (a purpose prompt).', hex: '#d29922' },
  { outcome: 'REQUIRE_APPROVAL', band: 'policy', friction: 'A second authorised person must approve (four-eyes). Replaces the banded outcome; initiator cannot self-approve.', hex: '#a371f7' },
  { outcome: 'HOLD', band: '60–84', friction: 'Cooling-off (30 min default) + a scam-pattern-naming warning + one-tap cancel. Never auto-executes.', hex: '#db6d28' },
  { outcome: 'BLOCK', band: '85+', friction: 'Stopped; manual review. Rare by design.', hex: '#f85149' },
];

export interface TypologyDoc {
  key: string;
  name: string;
  rule: string;
  meaning: string;
}

export const TYPOLOGIES: TypologyDoc[] = [
  { key: 'liquidation_kill_chain', name: 'Liquidation kill-chain', rule: 'K1>0.6 ∧ B1 ∧ A2>0.6', meaning: 'A term deposit was broken to free funds, which are now being drained to a brand-new payee — the headline scam sequence.' },
  { key: 'safe_account_scam', name: 'Safe-account scam', rule: 'B1 ∧ A2>0.7 ∧ (B3 ∨ D1>0.5 ∨ G1>0.5)', meaning: 'A coached victim moving most of their balance to a "safe account" — new payee, drain, often a new device or geo.' },
  { key: 'invoice_redirection', name: 'Invoice redirection (BEC)', rule: 'B5 ∧ A3 on an established counterparty', meaning: 'A known supplier’s account name / reference has changed — business-email-compromise redirection.' },
  { key: 'purchase_scam', name: 'Purchase scam', rule: 'B1 ∧ rail ∈ {P2P, INSTANT} ∧ first-time modest amount', meaning: 'A first-time instant / P2P payment to a brand-new payee for a marketplace "bargain".' },
  { key: 'vulnerability_escalation', name: 'Vulnerability escalation', rule: 'rising amounts (V2) over days ∧ a newish payee (B2)', meaning: 'Romance / repeat-victim grooming: escalating amounts to a payee added recently. On flagged-vulnerable accounts this routes to approval instead of a plain hold (§8.2).' },
  { key: 'payroll_redirection', name: 'Payroll redirection', rule: 'established batch pattern ∧ ≥1 changed-ref line (B5)', meaning: 'One salary IBAN changed among many in a payroll file — that line is quarantined while the rest proceed.' },
  { key: 'mule_fan_out', name: 'Mule fan-out', rule: 'MP1>0.7 ∧ MP4>0.6', meaning: 'A compromised account draining via many fresh counterparties in one batch.' },
];
