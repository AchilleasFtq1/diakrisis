// API shapes — snake_case, matching the gateway/ops-service JSON exactly.

export type Outcome = 'ALLOW' | 'CONFIRM' | 'REQUIRE_APPROVAL' | 'HOLD' | 'BLOCK';

/** Server-paged list envelope from ops-service (`total` is the filtered count). */
export interface Page<T> {
  items: T[];
  page: number;
  size: number;
  total: number;
  total_pages: number;
}

export interface FeedEntry {
  event_id: string;
  account_id: string;
  initiator_sub: string;
  lifecycle_state: string;
  created_at: string | null;
  hold_expires_at: string | null;
  verdict: Outcome | null;
  score: number | null;
  typologies: string[] | null;
  reason_code: string | null;
  friction: string | null;
  amount_eur: number | null;
  event_type: string | null;
}

export interface Counters {
  total: number;
  by_lifecycle_state: Record<string, number>;
  by_outcome: Record<string, number>;
  confirmed_saves: number;
  false_positives: number;
  money_saved_cents: number;
  exemption_rate: number;
  p50_latency_ms: number;
}

export interface ApprovalEntry {
  event_id: string;
  state: string;
  initiator_user_id: string | null;
  hold_expires_at: string | null;
  batch_held_item_ids: string[] | null;
  created_at: string | null;
  amount_eur: number | null;
}

export interface Signal {
  id: string;
  value: number;
  weight: number;
  contribution: number;
  detail: string | null;
}

/** One line of a batch (mass payment) decision. */
export interface ItemResult {
  item_id: string;
  decision: Outcome | string;
  signals?: Signal[] | null;
}

export interface CounterpartyView {
  counterparty_key: string;
  name: string | null;
  iban: string | null;
  worst_outcome: string | null;
  flag_count: number;
  last_flagged_at: string | null;
  fan_in_accounts: number;
  pay_count: number;
  mean_amount_eur: number | null;
}

export interface OutcomeView {
  event_id: string;
  account_id: string | null;
  outcome: string;
  amount_eur: number | null;
  signal_pattern: string | null;
  at: string | null;
}

// /ops/decisions/{id} — the verbatim stored decision.
export interface DecisionDetail {
  event_id: string;
  engine_verdict: {
    score: number;
    decision: Outcome;
    sca_exempt: boolean;
    sca_required: boolean;
    friction: string | null;
    typologies: string[];
    signals: Signal[];
  };
  ai_co_judge?: {
    status?: string;
    decision?: Outcome | string;
    score?: number;
    reason?: string;
    agreement?: string;
  } | null;
  combined: { decision: Outcome; basis: string | null; reason_code: string | null };
  lifecycle?: { state?: string } | null;
  explanation: { customer: string | null; audit: string | null } | null;
  reason_code: string | null;
  latency_ms: number;
  items?: ItemResult[] | null;
  // Metadata injected by ops-service from the Decisions table, so the detail page is self-contained.
  account_id?: string | null;
  hold_expires_at?: string | null;
  amount_eur?: number | null;
  event_type?: string | null;
  created_at?: string | null;
  initiator_sub?: string | null;
  lifecycle_state?: string | null;
  // Per-event request context, persisted at decision time (null on pre-existing decisions).
  channel?: string | null;
  ip?: string | null;
  network?: string | null;
  geo_country?: string | null;
  device_id?: string | null;
  device_platform?: string | null;
  session_id?: string | null;
  rail?: string | null;
  counterparty_name?: string | null;
  counterparty_ref?: string | null;
  counterparty_addressing?: string | null;
  event_ts?: string | null;
}

export interface AccountView {
  account_id: string;
  posture: {
    funds_freed_eur72h: number;
    limit_raised_eur72h: number;
    beneficiary_add_count72h: number;
  } | null;
  observations: { kind: string; value: string; first_seen_at: string | null }[];
  history: FeedEntry[];
}

export interface Session {
  token: string;
  refresh_token?: string | null;
  expires_at?: string | null;
  sub: string;
  roles: string[];
}
