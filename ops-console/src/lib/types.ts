// API shapes — snake_case, matching the gateway/ops-service JSON exactly.

export type Outcome = 'ALLOW' | 'CONFIRM' | 'REQUIRE_APPROVAL' | 'HOLD' | 'BLOCK';

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
  sub: string;
  roles: string[];
}
