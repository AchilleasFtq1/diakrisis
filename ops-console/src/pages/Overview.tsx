import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { api } from '../lib/api';
import { PageHead } from '../components/Layout';
import { StatCard, LiveToggle, Panel, Pagination, SearchInput } from '../components/widgets';
import { OutcomePill, ScoreMeter, NeedsReview, Mono, ColHint } from '../components/primitives';
import { euro, timeAgo } from '../lib/format';
import type { Outcome } from '../lib/types';

const OUTCOME_HEX: Record<string, string> = {
  ALLOW: '#3FB950',
  CONFIRM: '#D29922',
  REQUIRE_APPROVAL: '#A371F7',
  HOLD: '#DB6D28',
  BLOCK: '#F85149',
};
const ORDER: Outcome[] = ['ALLOW', 'CONFIRM', 'REQUIRE_APPROVAL', 'HOLD', 'BLOCK'];
const PAGE_SIZE = 15;

export default function Overview() {
  const navigate = useNavigate();
  const [live, setLive] = useState(true);
  const interval = live ? 4000 : false;

  const [outcomes, setOutcomes] = useState<Outcome[]>([]);
  const [query, setQuery] = useState('');
  const [page, setPage] = useState(1);

  const counters = useQuery({ queryKey: ['counters'], queryFn: api.counters, refetchInterval: interval });
  // Server-paged + server-filtered: the page index, outcome filter and search are query params.
  const feed = useQuery({
    queryKey: ['feed', page, outcomes, query],
    queryFn: () => api.feed({ page, size: PAGE_SIZE, outcomes, q: query }),
    refetchInterval: interval,
    placeholderData: keepPreviousData,
  });

  const c = counters.data;
  const totalDecisions = c?.total ?? 0;
  const money = c ? euro(c.money_saved_cents / 100) : '—';
  const slaOk = (c?.p50_latency_ms ?? 0) < 50;

  const rows = feed.data?.items ?? [];
  const matchTotal = feed.data?.total ?? 0;
  const filtersActive = outcomes.length > 0 || query.length > 0;

  const toggleOutcome = (o: Outcome) => {
    setOutcomes((prev) => (prev.includes(o) ? prev.filter((x) => x !== o) : [...prev, o]));
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
          <StatCard label="Total decisions" value={totalDecisions.toLocaleString()} hint="Every action the engine has scored." />
          <StatCard
            label="p50 latency"
            value={<span style={{ color: slaOk ? '#3FB950' : '#F85149' }}>{c?.p50_latency_ms ?? '—'}<span className="text-[14px] text-muted ml-1">ms</span></span>}
            sub={<span className="flex items-center gap-1.5"><span className={`w-1.5 h-1.5 rounded-full ${slaOk ? 'bg-allow' : 'bg-block'}`} />{slaOk ? 'within < 50ms SLA' : 'over SLA'}</span>}
            hint="Median decision latency. The deterministic decision path targets under 50ms."
          />
          <StatCard label="Confirmed saves" value={c?.confirmed_saves ?? '—'} accent="#3FB950" sub="frauds stopped" hint="Held actions a customer cancelled — true catches (money protected)." />
          <StatCard label="False positives" value={c?.false_positives ?? '—'} sub="released after expiry" hint="Held actions released after the hold expired — the engine interrupted a legit payment." />
          <StatCard label="Money protected" value={money} accent="#4CC2D6" hint="Total amount on confirmed-save actions." />
          <StatCard
            label="SCA-exemption rate"
            value={c ? `${Math.round(c.exemption_rate * 100)}%` : '—'}
            sub="ALLOW transfers, PSD2 TRA"
            hint="Share of transfers cleared with no SCA friction under PSD2 RTS Art. 18 (TRA)."
          />
          {/* by-outcome breakdown spanning two cards */}
          <div className="bg-panel border border-line rounded-xl p-4 col-span-2">
            <div className="font-mono text-[10px] tracking-[0.1em] uppercase text-muted mb-3">Outcome breakdown</div>
            <div className="flex h-2.5 rounded overflow-hidden bg-panel-2 border border-line">
              {ORDER.map((o) => {
                const n = c?.by_outcome?.[o] ?? 0;
                const pct = totalDecisions > 0 ? (n / totalDecisions) * 100 : 0;
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
          {/* outcome filter chips (server-side filter) */}
          <div className="flex items-center gap-1.5 flex-wrap mb-3.5">
            {ORDER.map((o) => {
              const active = outcomes.includes(o);
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
              {filtersActive ? `${matchTotal} matching` : `${matchTotal} shown`}
            </span>
            {filtersActive && (
              <button
                onClick={() => {
                  setOutcomes([]);
                  setQuery('');
                  setPage(1);
                }}
                className="font-mono text-[10.5px] text-cyan hover:text-cyan-2 ml-1"
              >
                clear
              </button>
            )}
          </div>

          {/* top pager — visible without scrolling the table */}
          <div className="border-y border-line/70 -mt-1 mb-1">
            <Pagination page={feed.data?.page ?? page} pageSize={PAGE_SIZE} total={matchTotal} onPage={setPage} />
          </div>

          <div className="overflow-x-auto">
            <table className="w-full text-[12.5px]">
              <thead>
                <tr className="text-left font-mono text-[10px] tracking-[0.08em] uppercase text-muted border-b border-line">
                  <th className="py-2 pr-3 font-medium">Time<ColHint text="How long ago the action was scored." /></th>
                  <th className="py-2 pr-3 font-medium">Account<ColHint text="The customer account that initiated the action." /></th>
                  <th className="py-2 pr-3 font-medium">Type<ColHint text="Action type: TRANSFER, MASS_PAYMENT, TERM_DEPOSIT_BREAK, etc." /></th>
                  <th className="py-2 pr-3 font-medium text-right">Amount<ColHint text="Money amount of the action, in euro." /></th>
                  <th className="py-2 pr-3 font-medium">Outcome<ColHint text="The engine's verdict: ALLOW, CONFIRM, REQUIRE_APPROVAL, HOLD or BLOCK." /></th>
                  <th className="py-2 pr-3 font-medium">Score<ColHint text="Risk score 0–100 (sum of weighted signals). Higher = riskier." /></th>
                  <th className="py-2 pr-3 font-medium">Typologies<ColHint text="Named scam patterns that fired (e.g. liquidation_kill_chain)." /></th>
                  <th className="py-2 pr-3 font-medium">Lifecycle<ColHint text="Where the action is in its lifecycle (HELD, EXECUTED, PENDING_APPROVAL…)." /></th>
                </tr>
              </thead>
              <tbody>
                {rows.map((e) => (
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
                {rows.length === 0 && (
                  <tr>
                    <td colSpan={8} className="py-8 text-center text-muted">
                      {filtersActive ? 'No decisions match the current filters.' : 'No decisions yet — drive a payment in the bank.'}
                    </td>
                  </tr>
                )}
              </tbody>
            </table>
          </div>

          <Pagination page={feed.data?.page ?? page} pageSize={PAGE_SIZE} total={matchTotal} onPage={setPage} />
        </Panel>
      </div>
    </div>
  );
}
