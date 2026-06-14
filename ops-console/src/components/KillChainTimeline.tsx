import type { AccountView, FeedEntry } from '../lib/types';
import { euro, relToAnchor } from '../lib/format';

const DOT: Record<string, string> = {
  ALLOW: '#3FB950',
  CONFIRM: '#D29922',
  REQUIRE_APPROVAL: '#A371F7',
  HOLD: '#DB6D28',
  BLOCK: '#F85149',
};

/** TERM_DEPOSIT_BREAK → "Term deposit break". */
function humanType(t: string | null): string {
  if (!t) return 'Event';
  const lower = t.replace(/_/g, ' ').toLowerCase();
  return lower.charAt(0).toUpperCase() + lower.slice(1);
}

/**
 * The kill-chain timeline — the account's recent actions oldest→newest, laid out horizontally so the
 * escalation reads as a sequence. Each card is ALLOW-grade in isolation; the sequence is the scam.
 * Times are relative to the anchor (the decision being viewed). Styling escalates when this is a
 * recognised chain; otherwise it renders as neutral recent activity.
 */
export function KillChainTimeline({
  account,
  highlightEventId,
  anchorTime,
  isKillChain = false,
}: {
  account: AccountView;
  highlightEventId?: string;
  /** The viewed decision's own created_at — used as the anchor when it falls outside the history window. */
  anchorTime?: string | null;
  isKillChain?: boolean;
}) {
  const steps: FeedEntry[] = [...account.history].reverse(); // oldest first
  // Anchor the relative "T−…" offsets on the decision under review. If that decision is older than the
  // windowed history (so highlightEventId matches nothing), prefer its own created_at over the newest
  // step — otherwise every offset would silently be measured against the wrong reference point.
  const anchor =
    steps.find((s) => s.event_id === highlightEventId)?.created_at
    ?? anchorTime
    ?? steps[steps.length - 1]?.created_at
    ?? null;

  if (steps.length === 0) {
    return <div className="text-[12px] text-muted">No prior actions on this account.</div>;
  }

  return (
    <div className="relative overflow-x-auto pb-1">
      {/* connecting line */}
      <div
        className="absolute top-[12px] left-[7%] right-[7%] h-0.5"
        style={{ background: isKillChain ? 'linear-gradient(90deg,#D29922,#DB6D28 55%,#F85149)' : '#232B36' }}
      />
      <div className="flex gap-0 min-w-max">
        {steps.map((s, i) => {
          const active = s.event_id === highlightEventId;
          const color = s.verdict ? DOT[s.verdict] : '#5C6773';
          return (
            <div key={s.event_id} className="relative flex-1 min-w-[160px] pr-3.5 last:pr-0">
              <div
                className="w-[26px] h-[26px] rounded-full flex items-center justify-center font-mono text-[11px] font-semibold relative z-10"
                style={{
                  background: active ? color : '#0C1015',
                  border: `2px solid ${color}`,
                  color: active ? '#0C1015' : color,
                }}
              >
                {i + 1}
              </div>
              <div
                className="mt-3 rounded-lg p-3 border"
                style={{
                  background: active ? 'rgba(248,81,73,.07)' : '#0F1620',
                  borderColor: active ? 'rgba(248,81,73,.45)' : '#243042',
                  borderLeft: `2px solid ${color}`,
                }}
              >
                <div className="font-mono text-[10px]" style={{ color }}>
                  {relToAnchor(s.created_at, anchor)}
                </div>
                <div className="text-[12.5px] text-fg font-semibold mt-1.5">{humanType(s.event_type)}</div>
                <div className="font-mono text-[11px] text-fg-2 mt-1.5">{euro(s.amount_eur)}</div>
                <div
                  className="text-[10.5px] mt-1.5 font-mono"
                  style={{ color: active ? color : '#5C6773', fontWeight: active ? 600 : 400 }}
                >
                  {s.verdict ?? '—'}
                </div>
              </div>
            </div>
          );
        })}
      </div>
    </div>
  );
}
