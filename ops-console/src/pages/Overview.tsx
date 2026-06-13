import { useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { api } from '../lib/api';
import { PageHead } from '../components/Layout';
import { StatCard, LiveToggle, Panel, Pagination, SearchInput } from '../components/widgets';
import { OutcomePill, ScoreMeter, NeedsReview, Mono } from '../components/primitives';
import { euro, timeAgo } from '../lib/format';
import type { FeedEntry, Outcome } from '../lib/types';

const OUTCOME_HEX: Record<string, string> = {
  ALLOW: '#3FB950',
  CONFIRM: '#D29922',
  REQUIRE_APPROVAL: '#A371F7',
  HOLD: '#DB6D28',
  BLOCK: '#F85149',
};
const ORDER: Outcome[] = ['ALLOW', 'CONFIRM', 'REQUIRE_APPROVAL', 'HOLD', 'BLOCK'];
const PAGE_SIZE = 15;

function matchesQuery(e: FeedEntry, q: string): boolean {
  if (!q) return true;
  const hay = [e.account_id, e.event_type, e.reason_code, e.initiator_sub, ...(e.typologies ?? [])]
    .filter(Boolean)
    .join(' ')
    .toLowerCase();
  return hay.includes(q.toLowerCase());
}

export default function Overview() {
  const navigate = useNavigate();
  const [live, setLive] = useState(true);
  const interval = live ? 4000 : false;

  const [outcomes, setOutcomes] = useState<Set<Outcome>>(new Set());
  const [query, setQuery] = useState('');
  const [page, setPage] = useState(1);

  const counters = useQuery({ queryKey: ['counters'], queryFn: api.counters, refetchInterval: interval });
  const feed = useQuery({ queryKey: ['feed'], queryFn: api.feed, refetchInterval: interval });

  const c = counters.data;
  const total = c?.total ?? 0;
  const money = c ? euro(c.money_saved_cents / 100) : '—';
  const slaOk = (c?.p50_latency_ms ?? 0) < 50;

  const filtered = useMemo(() => {
    const rows = feed.data ?? [];
    return rows.filter((e) => (outcomes.size === 0 || (e.verdict && outcomes.has(e.verdict))) && matchesQuery(e, query));
  }, [feed.data, outcomes, query]);

  const pageCount = Math.max(1, Math.ceil(filtered.length / PAGE_SIZE));
  const safePage = Math.min(page, pageCount);
  const pageRows = filtered.slice((safePage - 1) * PAGE_SIZE, safePage * PAGE_SIZE);

  const toggleOutcome = (o: Outcome) => {
    setOutcomes((prev) => {
      const next = new Set(prev);
      next.has(o) ? next.delete(o) : next.add(o);
      return next;
    });
    setPage(1);
  };
  const onQuery = (v: string) => {
    setQuery(v);
    setPage(1);
  };

  return (
    <div>
      <PageHead
        eyebrow="Live engine"
        title="Overview"
        right={<LiveToggle live={live} onToggle={() => setLive((v) => !v)} />}
      />

      <div className="px-8 py-6 space-y-6">
        {/* KPI cards */}
        <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
          <StatCard label="Total decisions" value={total.toLocaleString()} />
          <StatCard
            label="p50 latency"
            value={<span style={{ color: slaOk ? '#3FB950' : '#F85149' }}>{c?.p50_latency_ms ?? '—'}<span className="text-[14px] text-muted ml-1">ms</span></span>}
            sub={<span className="flex items-center gap-1.5"><span className={`w-1.5 h-1.5 rounded-full ${slaOk ? 'bg-allow' : 'bg-block'}`} />{slaOk ? 'within < 50ms SLA' : 'over SLA'}</span>}
          />
          <StatCard label="Confirmed saves" value={c?.confirmed_saves ?? '—'} accent="#3FB950" sub="frauds stopped" />
          <StatCard label="False positives" value={c?.false_positives ?? '—'} sub="released after expiry" />
          <StatCard label="Money protected" value={money} accent="#4CC2D6" />
          <StatCard
            label="SCA-exemption rate"
            value={c ? `${Math.round(c.exemption_rate * 100)}%` : '—'}
            sub="ALLOW transfers, PSD2 TRA"
          />
          {/* by-outcome breakdown spanning two cards */}
          <div className="bg-panel border border-line rounded-xl p-4 col-span-2">
            <div className="font-mono text-[10px] tracking-[0.1em] uppercase text-muted mb-3">Outcome breakdown</div>
            <div className="flex h-2.5 rounded overflow-hidden bg-panel-2 border border-line">
              {ORDER.map((o) => {
                const n = c?.by_outcome?.[o] ?? 0;
                const pct = total > 0 ? (n / total) * 100 : 0;
                return pct > 0 ? <div key={o} style={{ width: `${pct}%`, background: OUTCOME_HEX[o] }} /> : null;
              })}
            </div>
            <div className="flex flex-wrap gap-x-4 gap-y-1 mt-3">
              {ORDER.map((o) => (
                <span key={o} className="flex items-center gap-1.5 font-mono text-[10.5px] text-fg-2">
                  <span className="w-2 h-2 rounded-sm" style={{ background: OUTCOME_HEX[o] }} />
                  {o} <span className="text-fg">{c?.by_outcome?.[o] ?? 0}</span>
                </span>
              ))}
            </div>
          </div>
        </div>

        {/* live decision feed */}
        <Panel
          title="Live decision feed"
          right={
            <div className="flex items-center gap-3">
              {feed.isFetching && live && <span className="font-mono text-[10px] text-cyan">syncing…</span>}
              <SearchInput value={query} onChange={onQuery} placeholder="account · type · typology…" />
            </div>
          }
        >
          {/* outcome filter chips */}
          <div className="flex items-center gap-1.5 flex-wrap mb-3.5">
            {ORDER.map((o) => {
              const active = outcomes.has(o);
              const hex = OUTCOME_HEX[o];
              return (
                <button
                  key={o}
                  onClick={() => toggleOutcome(o)}
                  className="font-mono text-[10.5px] px-2 py-1 rounded border transition-colors"
                  style={
                    active
                      ? { color: hex, background: `${hex}1f`, borderColor: `${hex}66` }
                      : { color: '#5C6773', background: 'transparent', borderColor: '#232B36' }
                  }
                >
                  {o}
                </button>
              );
            })}
            <span className="font-mono text-[10.5px] text-muted ml-1">
              {filtered.length === (feed.data?.length ?? 0)
                ? `${filtered.length} shown`
                : `${filtered.length} of ${feed.data?.length ?? 0}`}
            </span>
            {(outcomes.size > 0 || query) && (
              <button
                onClick={() => {
                  setOutcomes(new Set());
                  setQuery('');
                  setPage(1);
                }}
                className="font-mono text-[10.5px] text-cyan hover:text-cyan-2 ml-1"
              >
                clear
              </button>
            )}
          </div>

          <div className="overflow-x-auto">
            <table className="w-full text-[12.5px]">
              <thead>
                <tr className="text-left font-mono text-[10px] tracking-[0.08em] uppercase text-muted border-b border-line">
                  <th className="py-2 pr-3 font-medium">Time</th>
                  <th className="py-2 pr-3 font-medium">Account</th>
                  <th className="py-2 pr-3 font-medium">Type</th>
                  <th className="py-2 pr-3 font-medium text-right">Amount</th>
                  <th className="py-2 pr-3 font-medium">Outcome</th>
                  <th className="py-2 pr-3 font-medium">Score</th>
                  <th className="py-2 pr-3 font-medium">Typologies</th>
                  <th className="py-2 pr-3 font-medium">Lifecycle</th>
                </tr>
              </thead>
              <tbody>
                {pageRows.map((e) => (
                  <tr
                    key={e.event_id}
                    onClick={() => navigate(`/decisions/${encodeURIComponent(e.event_id)}`)}
                    className="border-b border-line/60 hover:bg-white/[0.03] cursor-pointer"
                  >
                    <td className="py-2.5 pr-3 text-muted whitespace-nowrap">{timeAgo(e.created_at)}</td>
                    <td className="py-2.5 pr-3"><Mono className="text-fg">{e.account_id ?? '—'}</Mono></td>
                    <td className="py-2.5 pr-3 text-fg-2">{e.event_type ?? <NeedsReview label="type" />}</td>
                    <td className="py-2.5 pr-3 text-right font-mono text-fg tabular-nums">{euro(e.amount_eur)}</td>
                    <td className="py-2.5 pr-3"><OutcomePill outcome={e.verdict} /></td>
                    <td className="py-2.5 pr-3"><ScoreMeter score={e.score} /></td>
                    <td className="py-2.5 pr-3">
                      <div className="flex gap-1 flex-wrap">
                        {e.typologies && e.typologies.length > 0
                          ? e.typologies.map((t) => (
                              <span key={t} className="font-mono text-[10px] text-block bg-block/10 border border-block/25 rounded px-1.5 py-0.5">{t}</span>
                            ))
                          : <span className="text-muted">—</span>}
                      </div>
                    </td>
                    <td className="py-2.5 pr-3 font-mono text-[11px] text-fg-2">{e.lifecycle_state}</td>
                  </tr>
                ))}
                {filtered.length === 0 && (
                  <tr>
                    <td colSpan={8} className="py-8 text-center text-muted">
                      {(feed.data?.length ?? 0) === 0
                        ? 'No decisions yet — drive a payment in the bank.'
                        : 'No decisions match the current filters.'}
                    </td>
                  </tr>
                )}
              </tbody>
            </table>
          </div>

          <Pagination page={safePage} pageSize={PAGE_SIZE} total={filtered.length} onPage={setPage} />
        </Panel>
      </div>
    </div>
  );
}
