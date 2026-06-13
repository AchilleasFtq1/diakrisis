import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { ShieldCheck, AlertTriangle, Check } from 'lucide-react';
import type { ComponentType } from 'react';
import { api } from '../lib/api';
import { PageHead } from '../components/Layout';
import { Panel, Pagination, StatCard } from '../components/widgets';
import { Mono } from '../components/primitives';
import { euro, timeAgo } from '../lib/format';

const PAGE_SIZE = 15;
type Filter = '' | 'CONFIRMED_SAVE' | 'FALSE_POSITIVE';

const TABS: { key: Filter; label: string }[] = [
  { key: '', label: 'All' },
  { key: 'CONFIRMED_SAVE', label: 'Confirmed saves' },
  { key: 'FALSE_POSITIVE', label: 'False positives' },
];

/** Outcome → colour + icon. Unknown outcomes fall back to neutral. */
const OUTCOME_STYLE: Record<string, { hex: string; icon: ComponentType<{ size?: number }> }> = {
  CONFIRMED_SAVE: { hex: '#3fb950', icon: ShieldCheck },
  FALSE_POSITIVE: { hex: '#db6d28', icon: AlertTriangle },
  APPROVED: { hex: '#4cc2d6', icon: Check },
};

/** The signal_pattern can be a long, repeated per-line string on batches — dedupe + cap for display. */
function uniqueSignals(pattern: string | null): string {
  if (!pattern) return '—';
  const seen = new Set<string>();
  for (const raw of pattern.split(',')) {
    const s = raw.trim();
    if (s) seen.add(s);
  }
  const list = [...seen];
  return list.slice(0, 12).join(' ') + (list.length > 12 ? ` +${list.length - 12}` : '');
}

export default function Outcomes() {
  const navigate = useNavigate();
  const [type, setType] = useState<Filter>('');
  const [page, setPage] = useState(1);

  const counters = useQuery({ queryKey: ['counters'], queryFn: api.counters });
  const outcomes = useQuery({
    queryKey: ['outcomes', page, type],
    queryFn: () => api.outcomes({ page, size: PAGE_SIZE, type: type || undefined }),
    refetchInterval: 8000,
    placeholderData: keepPreviousData,
  });

  const rows = outcomes.data?.items ?? [];
  const total = outcomes.data?.total ?? 0;
  const c = counters.data;

  return (
    <div>
      <PageHead eyebrow="Resolution log" title="Outcomes" />
      <div className="px-8 py-6 space-y-6">
        <div className="grid grid-cols-2 md:grid-cols-3 gap-4">
          <StatCard label="Confirmed saves" value={c?.confirmed_saves ?? '—'} accent="#3fb950" sub="true catches" />
          <StatCard label="Money protected" value={c ? euro(c.money_saved_cents / 100) : '—'} accent="#4cc2d6" />
          <StatCard label="False positives" value={c?.false_positives ?? '—'} accent="#db6d28" sub="holds on legit pays" />
        </div>

        <Panel
          title="Recorded outcomes"
          right={<span className="font-mono text-[10.5px] text-muted">{total} total</span>}
        >
          <div className="flex items-center gap-1.5 mb-3.5">
            {TABS.map((t) => {
              const active = type === t.key;
              return (
                <button
                  key={t.key}
                  onClick={() => {
                    setType(t.key);
                    setPage(1);
                  }}
                  className={`font-mono text-[11px] px-2.5 py-1 rounded border transition-colors ${
                    active ? 'text-cyan bg-cyan/15 border-cyan/50' : 'text-fg-2 bg-panel-2 border-line hover:text-fg'
                  }`}
                >
                  {t.label}
                </button>
              );
            })}
          </div>

          {total > PAGE_SIZE && (
            <div className="border-y border-line/70 mb-2 -mt-1">
              <Pagination page={outcomes.data?.page ?? page} pageSize={PAGE_SIZE} total={total} onPage={setPage} />
            </div>
          )}

          <div className="overflow-x-auto">
            <table className="w-full text-[12.5px]">
              <thead>
                <tr className="text-left font-mono text-[10px] tracking-[0.08em] uppercase text-muted border-b border-line">
                  <th className="py-2 pr-3 font-medium">When</th>
                  <th className="py-2 pr-3 font-medium">Outcome</th>
                  <th className="py-2 pr-3 font-medium text-right">Amount</th>
                  <th className="py-2 pr-3 font-medium">Account</th>
                  <th className="py-2 pr-3 font-medium">Signal pattern</th>
                </tr>
              </thead>
              <tbody>
                {rows.map((o) => {
                  const style = OUTCOME_STYLE[o.outcome] ?? { hex: '#93a1b0', icon: AlertTriangle };
                  const Icon = style.icon;
                  const save = o.outcome === 'CONFIRMED_SAVE';
                  return (
                    <tr
                      key={o.event_id}
                      onClick={() => navigate(`/decisions/${encodeURIComponent(o.event_id)}`)}
                      className="border-b border-line/60 hover:bg-white/[0.03] cursor-pointer"
                    >
                      <td className="py-2.5 pr-3 text-muted whitespace-nowrap">{timeAgo(o.at)}</td>
                      <td className="py-2.5 pr-3">
                        <span
                          className="inline-flex items-center gap-1.5 font-mono text-[10.5px] px-2 py-0.5 rounded border"
                          style={{ color: style.hex, background: `${style.hex}1a`, borderColor: `${style.hex}55` }}
                        >
                          <Icon size={12} />
                          {o.outcome}
                        </span>
                      </td>
                      <td className="py-2.5 pr-3 text-right font-mono tabular-nums" style={{ color: save ? '#3fb950' : '#e6edf3' }}>
                        {euro(o.amount_eur)}
                      </td>
                      <td className="py-2.5 pr-3"><Mono className="text-fg-2">{o.account_id ?? '—'}</Mono></td>
                      <td className="py-2.5 pr-3 max-w-[280px]">
                        <Mono className="text-[11px] text-muted block truncate" >{uniqueSignals(o.signal_pattern)}</Mono>
                      </td>
                    </tr>
                  );
                })}
                {rows.length === 0 && (
                  <tr><td colSpan={5} className="py-8 text-center text-muted">No outcomes recorded yet.</td></tr>
                )}
              </tbody>
            </table>
          </div>
          <Pagination page={outcomes.data?.page ?? page} pageSize={PAGE_SIZE} total={total} onPage={setPage} />
        </Panel>
      </div>
    </div>
  );
}
