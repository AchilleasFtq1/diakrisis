import { Link, useParams } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { ArrowLeft, Cpu } from 'lucide-react';
import { api } from '../lib/api';
import { PageHead } from '../components/Layout';
import { Panel, SignalBar } from '../components/widgets';
import { OutcomePill, ScoreMeter, NeedsReview, Mono } from '../components/primitives';
import { KillChainTimeline } from '../components/KillChainTimeline';
import { euro } from '../lib/format';

export default function DecisionDetail() {
  const { id = '' } = useParams();
  const decision = useQuery({ queryKey: ['decision', id], queryFn: () => api.decision(id) });
  const feed = useQuery({ queryKey: ['feed'], queryFn: api.feed });

  const entry = feed.data?.find((e) => e.event_id === id);
  const accountId = entry?.account_id;
  const account = useQuery({
    queryKey: ['account', accountId],
    queryFn: () => api.account(accountId!),
    enabled: !!accountId,
  });

  const d = decision.data;
  const ev = d?.engine_verdict;
  const signals = ev?.signals ? [...ev.signals].sort((a, b) => Math.abs(b.contribution) - Math.abs(a.contribution)) : [];
  const maxContribution = signals.reduce((m, s) => Math.max(m, Math.abs(s.contribution)), 1);
  const ai = d?.ai_co_judge;
  const aiUnavailable = !ai || ai.status === 'UNAVAILABLE' || (!ai.decision && !ai.score);

  return (
    <div>
      <PageHead
        eyebrow="Decision detail"
        title={entry?.event_type ? `${entry.event_type} · ${euro(entry.amount_eur)}` : 'Decision'}
        right={
          <Link to="/overview" className="flex items-center gap-1.5 text-[12px] text-fg-2 hover:text-fg">
            <ArrowLeft size={14} /> Back to feed
          </Link>
        }
      />

      <div className="px-8 py-6 grid grid-cols-1 xl:grid-cols-[1.4fr_1fr] gap-6">
        {/* left column */}
        <div className="space-y-6">
          <Panel>
            <div className="flex items-center gap-4 flex-wrap">
              {d ? <OutcomePill outcome={d.combined?.decision ?? ev?.decision ?? null} /> : null}
              <ScoreMeter score={ev?.score ?? null} wide />
              <div className="ml-auto text-right">
                <div className="font-mono text-[10px] text-muted uppercase tracking-wide">latency</div>
                <div className="font-mono text-fg">{d ? `${d.latency_ms} ms` : '—'}</div>
              </div>
            </div>
            <div className="mt-3 flex items-center gap-2 flex-wrap">
              <Mono className="text-[11px] text-muted">{id}</Mono>
              {d?.reason_code && (
                <span className="font-mono text-[10.5px] text-cyan bg-cyan/10 border border-cyan/25 rounded px-1.5 py-0.5">
                  {d.reason_code}
                </span>
              )}
            </div>
            {ev?.typologies && ev.typologies.length > 0 && (
              <div className="mt-3 flex gap-1.5 flex-wrap">
                {ev.typologies.map((t) => (
                  <span key={t} className="font-mono text-[11px] text-block bg-block/10 border border-block/25 rounded px-2 py-0.5">{t}</span>
                ))}
              </div>
            )}
          </Panel>

          <Panel title="Signal contributions — how the score was reached">
            {signals.length > 0 ? (
              <div>
                <div className="flex items-center gap-3 pb-1 font-mono text-[9.5px] uppercase tracking-wide text-muted">
                  <span className="w-9">id</span><span className="flex-1">contribution</span>
                  <span className="w-12 text-right">value</span><span className="w-12 text-right">weight</span><span className="w-12 text-right">Σ</span>
                </div>
                {signals.filter((s) => s.contribution !== 0).map((s) => <SignalBar key={s.id} signal={s} max={maxContribution} />)}
              </div>
            ) : (
              <NeedsReview label="no signal breakdown" />
            )}
          </Panel>

          <Panel title="Customer-facing explanation (what the bank showed)">
            {d?.explanation?.customer ? (
              <p className="text-[13.5px] text-fg leading-relaxed">{d.explanation.customer}</p>
            ) : (
              <p className="text-[13px] text-muted">Silent — nothing shown to the customer (clean ALLOW).</p>
            )}
            {d?.explanation?.audit && <p className="mt-2 font-mono text-[11px] text-muted">{d.explanation.audit}</p>}
          </Panel>
        </div>

        {/* right column */}
        <div className="space-y-6">
          <Panel title="Engine vs AI co-judge">
            <div className="flex items-center gap-3">
              <Cpu size={16} className="text-cyan" />
              <div className="flex-1">
                <div className="text-[11px] text-muted">Engine verdict</div>
                <div className="mt-1"><OutcomePill outcome={ev?.decision ?? null} /></div>
              </div>
              <div className="flex-1">
                <div className="text-[11px] text-muted">AI co-judge</div>
                <div className="mt-1">{aiUnavailable ? <NeedsReview label="UNAVAILABLE" /> : <Mono className="text-fg">{ai?.decision ?? '—'}</Mono>}</div>
              </div>
            </div>
            <p className="mt-3 text-[11.5px] text-muted">
              The co-judge is advisory and can only escalate — it defaults to UNAVAILABLE and never blocks the decision path.
            </p>
          </Panel>

          <Panel title="Kill-chain timeline — what the engine remembered">
            {account.data ? (
              <KillChainTimeline account={account.data} highlightEventId={id} />
            ) : accountId ? (
              <div className="text-[12px] text-muted">Loading account history…</div>
            ) : (
              <NeedsReview label="account unknown" />
            )}
            {accountId && (
              <Link to={`/accounts/${encodeURIComponent(accountId)}`} className="inline-block mt-3 text-[12px] text-cyan hover:text-cyan-2">
                View account posture →
              </Link>
            )}
          </Panel>
        </div>
      </div>
    </div>
  );
}
