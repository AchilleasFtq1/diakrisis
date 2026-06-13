import type { ReactNode } from 'react';
import { Pause, Play } from 'lucide-react';
import type { Signal } from '../lib/types';

export function StatCard({
  label,
  value,
  sub,
  accent,
}: {
  label: string;
  value: ReactNode;
  sub?: ReactNode;
  accent?: string;
}) {
  return (
    <div className="bg-panel border border-line rounded-xl p-4">
      <div className="font-mono text-[10px] tracking-[0.1em] uppercase text-muted mb-2">{label}</div>
      <div className="font-mono text-[26px] font-semibold leading-none" style={accent ? { color: accent } : undefined}>
        {value}
      </div>
      {sub && <div className="text-[11px] text-fg-2 mt-2">{sub}</div>}
    </div>
  );
}

export function LiveToggle({ live, onToggle }: { live: boolean; onToggle: () => void }) {
  return (
    <button
      onClick={onToggle}
      className={`flex items-center gap-2 font-mono text-[11px] px-3 py-1.5 rounded-lg border transition-colors ${
        live
          ? 'text-allow bg-allow/10 border-allow/30'
          : 'text-fg-2 bg-panel-2 border-line hover:text-fg'
      }`}
    >
      {live ? <Play size={12} /> : <Pause size={12} />}
      {live ? 'LIVE' : 'PAUSED'}
      {live && <span className="w-1.5 h-1.5 rounded-full bg-allow animate-pulse" />}
    </button>
  );
}

/** One signal's weighted contribution as a horizontal bar (id · value · weight · contribution). */
export function SignalBar({ signal, max }: { signal: Signal; max: number }) {
  const pct = max > 0 ? Math.abs(signal.contribution / max) * 100 : 0;
  const negative = signal.contribution < 0;
  return (
    <div className="flex items-center gap-3 py-1.5">
      <span className="font-mono text-[11px] text-fg w-9 shrink-0">{signal.id}</span>
      <div className="flex-1 h-[8px] rounded bg-panel-2 border border-line overflow-hidden relative">
        <div
          className="h-full rounded"
          style={{
            width: `${Math.min(100, pct)}%`,
            background: negative ? '#3FB950' : 'linear-gradient(90deg,#D29922,#F85149)',
          }}
        />
      </div>
      <span className="font-mono text-[10px] text-fg-2 w-12 shrink-0 text-right tabular-nums">
        v {signal.value.toFixed(2)}
      </span>
      <span className="font-mono text-[10px] text-muted w-12 shrink-0 text-right tabular-nums">
        w {signal.weight.toFixed(0)}
      </span>
      <span
        className="font-mono text-[11px] w-12 shrink-0 text-right tabular-nums font-semibold"
        style={{ color: negative ? '#3FB950' : '#E6EDF3' }}
      >
        {negative ? '' : '+'}
        {signal.contribution.toFixed(1)}
      </span>
    </div>
  );
}

export function Panel({ title, children, right }: { title?: string; children: ReactNode; right?: ReactNode }) {
  return (
    <section className="bg-panel border border-line rounded-xl p-5">
      {title && (
        <div className="flex items-center justify-between mb-3.5">
          <h2 className="font-mono text-[11px] tracking-[0.1em] uppercase text-muted">{title}</h2>
          {right}
        </div>
      )}
      {children}
    </section>
  );
}
