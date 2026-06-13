import type { ReactNode } from 'react';
import { ChevronLeft, ChevronRight, Pause, Play, Search, X } from 'lucide-react';
import type { Signal } from '../lib/types';

export function StatCard({
  label,
  value,
  sub,
  accent,
  hint,
}: {
  label: string;
  value: ReactNode;
  sub?: ReactNode;
  accent?: string;
  hint?: string;
}) {
  return (
    <div className="bg-panel border border-line rounded-xl p-4">
      <div className="font-mono text-[10px] tracking-[0.1em] uppercase text-muted mb-2" title={hint}>
        {label}{hint && <span className="text-muted/50 ml-1 cursor-help">ⓘ</span>}
      </div>
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

/** The page numbers to show, with `'…'` gaps, keeping the current page centred in a window of 7. */
function pageWindow(page: number, pageCount: number): (number | '…')[] {
  if (pageCount <= 7) return Array.from({ length: pageCount }, (_, i) => i + 1);
  const out: (number | '…')[] = [1];
  const start = Math.max(2, page - 1);
  const end = Math.min(pageCount - 1, page + 1);
  if (start > 2) out.push('…');
  for (let p = start; p <= end; p++) out.push(p);
  if (end < pageCount - 1) out.push('…');
  out.push(pageCount);
  return out;
}

/**
 * Pager over a server-paged list. Numbered buttons (current page highlighted) plus prev/next, so the
 * paging controls read as paging at a glance. Renders nothing when a single page suffices.
 */
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
  const arrow = 'flex items-center justify-center w-[26px] h-[26px] rounded border border-line bg-panel-2 text-fg-2 hover:text-fg hover:border-cyan disabled:opacity-30 disabled:cursor-not-allowed disabled:hover:border-line';
  return (
    <div className="flex items-center justify-between gap-3 py-2.5 flex-wrap">
      <span className="font-mono text-[11px] text-muted">{from}–{to} of {total}</span>
      <div className="flex items-center gap-1">
        <button className={arrow} disabled={page <= 1} onClick={() => onPage(page - 1)} title="Previous">
          <ChevronLeft size={14} />
        </button>
        {pageWindow(page, pageCount).map((p, i) =>
          p === '…' ? (
            <span key={`gap-${i}`} className="font-mono text-[11px] text-muted px-1 select-none">…</span>
          ) : (
            <button
              key={p}
              onClick={() => onPage(p)}
              aria-current={p === page}
              className={`font-mono text-[11.5px] min-w-[26px] h-[26px] px-1.5 rounded border transition-colors ${
                p === page
                  ? 'bg-cyan/15 border-cyan/50 text-cyan font-semibold'
                  : 'bg-panel-2 border-line text-fg-2 hover:text-fg hover:border-cyan'
              }`}
            >
              {p}
            </button>
          ),
        )}
        <button className={arrow} disabled={page >= pageCount} onClick={() => onPage(page + 1)} title="Next">
          <ChevronRight size={14} />
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
