import { Info } from 'lucide-react';
import type { Outcome } from '../lib/types';
import { scoreColor } from '../lib/format';

/**
 * A small ⓘ cue next to a column header that reveals a plain-language explanation on hover. Uses the
 * native title tooltip so it never clips inside the tables' overflow containers.
 */
export function ColHint({ text }: { text: string }) {
  return (
    <span title={text} className="inline-flex items-center align-middle cursor-help">
      <Info size={10} className="text-muted/60 hover:text-cyan ml-1 transition-colors" />
    </span>
  );
}

/* Outcome pill — full literal class strings (Tailwind v4 scans these). */
const PILL: Record<Outcome, string> = {
  ALLOW: 'text-allow bg-allow/12 border-allow/40',
  CONFIRM: 'text-confirm bg-confirm/12 border-confirm/40',
  REQUIRE_APPROVAL: 'text-approval bg-approval/12 border-approval/45',
  HOLD: 'text-hold bg-hold/12 border-hold/40',
  BLOCK: 'text-block bg-block/15 border-block/45',
};

export function OutcomePill({ outcome }: { outcome: Outcome | null }) {
  if (!outcome) return <NeedsReview label="no verdict" />;
  return (
    <span
      className={`font-mono text-[11px] font-semibold tracking-wide px-2.5 py-1 rounded-md border ${PILL[outcome]}`}
    >
      {outcome}
    </span>
  );
}

export function ScoreMeter({ score, wide = false }: { score: number | null; wide?: boolean }) {
  if (score == null) return <NeedsReview label="no score" />;
  // The colour bands on the raw score so a fractional value (e.g. 59.6) lands in the correct band, but
  // the displayed number and the meter width round to a whole point so the digit shown can never sit on
  // the wrong side of a band edge from the colour, and the feed and detail page agree for one decision.
  const rounded = Math.round(score);
  return (
    <div className="flex items-center gap-2.5">
      <span
        className="font-mono font-semibold tabular-nums leading-none"
        style={{ color: scoreColor(score), fontSize: wide ? 30 : 14 }}
      >
        {rounded}
      </span>
      <div
        className={`${wide ? 'w-44 h-[9px]' : 'w-20 h-[7px]'} rounded bg-panel-2 border border-line overflow-hidden`}
      >
        <div className="h-full severity-fill" style={{ width: `${Math.min(100, Math.max(0, rounded))}%` }} />
      </div>
    </div>
  );
}

export function TypologyChip({ name }: { name: string }) {
  return (
    <span className="inline-block font-mono text-[10.5px] text-block bg-block/10 border border-block/25 rounded px-1.5 py-0.5">
      {name}
    </span>
  );
}

/** The "flag for review" affordance — shown for any field the API could not provide. */
export function NeedsReview({ label = 'needs review' }: { label?: string }) {
  return (
    <span
      title="Data not available from the API — flagged for review, not fabricated."
      className="inline-flex items-center gap-1 font-mono text-[10px] text-confirm/80 bg-confirm/8 border border-confirm/30 border-dashed rounded px-1.5 py-0.5"
    >
      <span className="text-confirm">⚑</span> {label}
    </span>
  );
}

export function Mono({ children, className = '' }: { children: React.ReactNode; className?: string }) {
  return <span className={`font-mono ${className}`}>{children}</span>;
}
