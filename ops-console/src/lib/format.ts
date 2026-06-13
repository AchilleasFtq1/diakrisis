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

export function countdown(iso: string | null): string {
  if (!iso) return '—';
  const ms = new Date(iso).getTime() - Date.now();
  if (ms <= 0) return 'expired';
  const m = Math.floor(ms / 60000);
  const s = Math.floor((ms % 60000) / 1000);
  return m > 60 ? `${Math.floor(m / 60)}h ${m % 60}m` : `${m}m ${s}s`;
}
