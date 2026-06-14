import type { Outcome } from './types';

/** Outcome → the design's exact severity color token. */
export const OUTCOME_COLOR: Record<Outcome, string> = {
  ALLOW: 'allow',
  CONFIRM: 'confirm',
  REQUIRE_APPROVAL: 'approval',
  HOLD: 'hold',
  BLOCK: 'block',
};

export const OUTCOME_LABEL: Record<Outcome, string> = {
  ALLOW: 'execute',
  CONFIRM: 'step-up',
  REQUIRE_APPROVAL: 'four-eyes',
  HOLD: 'freeze',
  BLOCK: 'reject',
};

/**
 * The authoritative risk-score band edges (inclusive lower bound of each band), mirroring the engine's
 * Bands.java and the catalog OUTCOME_BANDS: ALLOW 0–29, CONFIRM 30–59, HOLD 60–84, BLOCK 85+. Every
 * surface that colours a score MUST band on these same edges so the feed and the detail page agree on
 * the severity colour for one and the same decision.
 */
export const SCORE_BAND_EDGES = { confirm: 30, hold: 60, block: 85 } as const;

/**
 * Map a (possibly fractional) risk score to its severity colour, using the authoritative band edges.
 * Single source of truth consumed by ScoreMeter (feed/account tables) and the DecisionDetail score card
 * so the same score is never coloured differently across screens. Defensive against null/NaN inputs.
 */
export function scoreColor(score: number | null | undefined): string {
  const value = typeof score === 'number' && Number.isFinite(score) ? score : 0;
  if (value >= SCORE_BAND_EDGES.block) return '#F85149'; // BLOCK  (85+)
  if (value >= SCORE_BAND_EDGES.hold) return '#DB6D28'; // HOLD   (60–84)
  if (value >= SCORE_BAND_EDGES.confirm) return '#D29922'; // CONFIRM (30–59)
  return '#3FB950'; // ALLOW (0–29)
}

export function euro(n: number | null | undefined): string {
  if (n == null) return '—';
  return '€' + n.toLocaleString('en-GB', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
}

export function timeAgo(iso: string | null): string {
  if (!iso) return '—';
  const then = new Date(iso).getTime();
  const secs = Math.max(0, Math.floor((Date.now() - then) / 1000));
  if (secs < 60) return `${secs}s ago`;
  if (secs < 3600) return `${Math.floor(secs / 60)}m ago`;
  if (secs < 86400) return `${Math.floor(secs / 3600)}h ago`;
  return new Date(iso).toLocaleDateString('en-GB', { day: 'numeric', month: 'short' });
}

export function clock(iso: string | null): string {
  if (!iso) return '—';
  return new Date(iso).toLocaleTimeString('en-GB', { hour: '2-digit', minute: '2-digit', second: '2-digit' });
}

/** Offset of `iso` relative to the anchor event, as the design's "T−4m12s" / "T−0s · NOW". */
export function relToAnchor(iso: string | null, anchorIso: string | null): string {
  if (!iso || !anchorIso) return '—';
  const secs = Math.round((new Date(anchorIso).getTime() - new Date(iso).getTime()) / 1000);
  if (secs <= 0) return 'T−0s · NOW';
  if (secs < 60) return `T−${secs}s`;
  const m = Math.floor(secs / 60);
  const s = secs % 60;
  if (m < 60) return `T−${m}m${s.toString().padStart(2, '0')}s`;
  const h = Math.floor(m / 60);
  return `T−${h}h${(m % 60).toString().padStart(2, '0')}m`;
}

export function countdown(iso: string | null): string {
  if (!iso) return '—';
  const ms = new Date(iso).getTime() - Date.now();
  if (ms <= 0) return 'expired';
  const m = Math.floor(ms / 60000);
  const s = Math.floor((ms % 60000) / 1000);
  return m > 60 ? `${Math.floor(m / 60)}h ${m % 60}m` : `${m}m ${s}s`;
}
