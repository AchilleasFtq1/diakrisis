import type { ReactNode } from 'react';
import { ChevronLeft, ChevronRight, Pause, Play, Search, X } from 'lucide-react';
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

/** Client-side pager over an already-fetched list. Renders nothing when a single page suffices. */
export function Pagination({
  page,
  pageSize,
  total,
  onPage,
}: {
  page: number;
  pageSize: number;
  total: number;
  onPage: (p: number) => void;
}) {
  const pageCount = Math.max(1, Math.ceil(total / pageSize));
  if (pageCount <= 1) return null;
  const from = total === 0 ? 0 : (page - 1) * pageSize + 1;
  const to = Math.min(total, page * pageSize);
  const btn = 'flex items-center gap-1 px-2.5 py-1 rounded border border-line bg-panel-2 text-fg-2 hover:text-fg hover:border-line-2 disabled:opacity-40 disabled:cursor-not-allowed';
  return (
    <div className="flex items-center justify-between mt-3.5 font-mono text-[11px] text-muted">
      <span>{from}–{to} of {total}</span>
      <div className="flex items-center gap-2">
        <button className={btn} disabled={page <= 1} onClick={() => onPage(page - 1)}>
          <ChevronLeft size={13} /> Prev
        </button>
        <span className="text-fg-2">page {page} / {pageCount}</span>
        <button className={btn} disabled={page >= pageCount} onClick={() => onPage(page + 1)}>
          Next <ChevronRight size={13} />
        </button>
      </div>
    </div>
  );
}

/** Debounce-free search box — caller filters synchronously over the fetched list. */
export function SearchInput({
  value,
  onChange,
  placeholder = 'Search…',
  width = 'w-60',
}: {
  value: string;
  onChange: (v: string) => void;
  placeholder?: string;
  width?: string;
}) {
  return (
    <div className={`relative ${width}`}>
      <Search size={13} className="absolute left-2.5 top-1/2 -translate-y-1/2 text-muted pointer-events-none" />
      <input
        value={value}
        onChange={(e) => onChange(e.target.value)}
        placeholder={placeholder}
        className="h-[34px] w-full rounded-lg bg-panel-2 border border-line pl-8 pr-7 font-mono text-[12px] text-fg outline-none focus:border-cyan placeholder:text-muted"
      />
      {value && (
        <button
          onClick={() => onChange('')}
          className="absolute right-2 top-1/2 -translate-y-1/2 text-muted hover:text-fg"
          title="Clear"
        >
          <X size={13} />
        </button>
      )}
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
