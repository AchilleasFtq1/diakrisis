import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { api } from '../lib/api';
import { PageHead } from '../components/Layout';
import { StatCard, LiveToggle, Panel } from '../components/widgets';
import { OutcomePill, ScoreMeter, NeedsReview, Mono } from '../components/primitives';
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

export default function Overview() {
  const navigate = useNavigate();
  const [live, setLive] = useState(true);
  const interval = live ? 4000 : false;

  const counters = useQuery({ queryKey: ['counters'], queryFn: api.counters, refetchInterval: interval });
  const feed = useQuery({ queryKey: ['feed'], queryFn: api.feed, refetchInterval: interval });

  const c = counters.data;
  const total = c?.total ?? 0;
  const money = c ? euro(c.money_saved_cents / 100) : '—';
  const slaOk = (c?.p50_latency_ms ?? 0) < 50;

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
        <Panel title="Live decision feed" right={feed.isFetching && live ? <span className="font-mono text-[10px] text-cyan">syncing…</span> : null}>
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
                {feed.data?.map((e) => (
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
                {feed.data?.length === 0 && (
                  <tr><td colSpan={8} className="py-8 text-center text-muted">No decisions yet — drive a payment in the bank.</td></tr>
                )}
              </tbody>
            </table>
          </div>
        </Panel>
      </div>
    </div>
  );
}
