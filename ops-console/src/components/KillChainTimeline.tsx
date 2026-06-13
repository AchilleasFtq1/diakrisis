import type { AccountView, FeedEntry } from '../lib/types';
import { OutcomePill } from './primitives';
import { euro, clock } from '../lib/format';
import { Snowflake } from 'lucide-react';

const DOT: Record<string, string> = {
  ALLOW: '#3FB950',
  CONFIRM: '#D29922',
  REQUIRE_APPROVAL: '#A371F7',
  HOLD: '#DB6D28',
  BLOCK: '#F85149',
};

/**
 * The kill-chain timeline — the account's recent actions oldest→newest, so the escalation
 * (deposit break → funds freed → drain → block) reads as a sequence. The funds-freed posture is the
 * memory that made the final block possible.
 */
export function KillChainTimeline({ account, highlightEventId }: { account: AccountView; highlightEventId?: string }) {
  const steps: FeedEntry[] = [...account.history].reverse(); // oldest first
  const fundsFreed = account.posture?.funds_freed_eur72h ?? 0;

  return (
    <div>
      {fundsFreed > 0 && (
        <div className="flex items-center gap-2.5 mb-4 px-3.5 py-2.5 rounded-lg bg-hold/10 border border-hold/30">
          <Snowflake size={15} className="text-hold" />
          <span className="text-[12.5px] text-fg">
            <span className="font-mono text-hold font-semibold">{euro(fundsFreed)}</span> freed in the last 72h —
            the posture the engine remembers when scoring the drain.
          </span>
        </div>
      )}

      <ol className="relative ml-2">
        <div className="absolute left-[5px] top-1 bottom-1 w-px bg-line" />
        {steps.map((s) => {
          const active = s.event_id === highlightEventId;
          const color = s.verdict ? DOT[s.verdict] : '#5C6773';
          return (
            <li key={s.event_id} className="relative pl-6 pb-5 last:pb-0">
              <span
                className="absolute left-0 top-1 w-[11px] h-[11px] rounded-full border-2"
                style={{ background: '#0C1015', borderColor: color }}
              />
              <div
                className={`rounded-lg border p-3 ${
                  active ? 'border-block/50 bg-block/5' : 'border-line bg-panel-2'
                }`}
              >
                <div className="flex items-center gap-2.5 flex-wrap">
                  <OutcomePill outcome={s.verdict} />
                  <span className="font-mono text-[11px] text-fg-2">{s.event_type ?? '—'}</span>
                  <span className="font-mono text-[11px] text-fg">{euro(s.amount_eur)}</span>
                  <span className="font-mono text-[10px] text-muted ml-auto">{clock(s.created_at)}</span>
                </div>
                {s.typologies && s.typologies.length > 0 && (
                  <div className="mt-2 flex gap-1 flex-wrap">
                    {s.typologies.map((t) => (
                      <span
                        key={t}
                        className="font-mono text-[10px] text-block bg-block/10 border border-block/25 rounded px-1.5 py-0.5"
                      >
                        {t}
                      </span>
                    ))}
                  </div>
                )}
              </div>
            </li>
          );
        })}
        {steps.length === 0 && <li className="pl-6 text-[12px] text-muted">No prior actions on this account.</li>}
      </ol>
    </div>
  );
}
