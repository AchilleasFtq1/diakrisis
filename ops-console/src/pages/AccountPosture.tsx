import { useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { Smartphone, Globe, Network, Snowflake } from 'lucide-react';
import { api } from '../lib/api';
import { PageHead } from '../components/Layout';
import { Panel, StatCard, Pagination } from '../components/widgets';
import { OutcomePill, ScoreMeter, NeedsReview, Mono } from '../components/primitives';
import { KillChainTimeline } from '../components/KillChainTimeline';
import { euro, timeAgo, clock } from '../lib/format';

const OBS_ICON: Record<string, typeof Smartphone> = {
  DEVICE: Smartphone,
  NETWORK: Network,
  IP: Network,
  GEO: Globe,
  ALIAS: Globe,
};

function AccountPicker() {
  // One large page is enough to enumerate the accounts currently in the recent feed.
  const feed = useQuery({ queryKey: ['feed', 'picker'], queryFn: () => api.feed({ size: 100 }) });
  const ids = Array.from(new Set((feed.data?.items ?? []).map((e) => e.account_id).filter(Boolean)));
  return (
    <div>
      <PageHead eyebrow="Accounts" title="Pick an account" />
      <div className="px-8 py-6">
        <Panel title="Accounts seen in the feed">
          <div className="flex flex-wrap gap-2">
            {ids.map((id) => (
              <Link
                key={id}
                to={`/accounts/${encodeURIComponent(id)}`}
                className="font-mono text-[12px] text-fg bg-panel-2 border border-line rounded-lg px-3 py-2 hover:border-cyan"
              >
                {id}
              </Link>
            ))}
            {ids.length === 0 && <span className="text-muted text-[13px]">No accounts yet.</span>}
          </div>
        </Panel>
      </div>
    </div>
  );
}

const HISTORY_PAGE_SIZE = 10;

export default function AccountPosture() {
  const { id } = useParams();
  const [page, setPage] = useState(1);
  const account = useQuery({
    queryKey: ['account', id],
    queryFn: () => api.account(id!),
    enabled: !!id,
    refetchInterval: 6000,
  });
  if (!id) return <AccountPicker />;

  const a = account.data;
  const p = a?.posture;
  const history = a?.history ?? [];
  const pageCount = Math.max(1, Math.ceil(history.length / HISTORY_PAGE_SIZE));
  const safePage = Math.min(page, pageCount);
  const historyPage = history.slice((safePage - 1) * HISTORY_PAGE_SIZE, safePage * HISTORY_PAGE_SIZE);

  return (
    <div>
      <PageHead eyebrow="Account" title={id} />
      <div className="px-8 py-6 grid grid-cols-1 xl:grid-cols-[1fr_1.2fr] gap-6">
        <div className="space-y-6">
          <div className="grid grid-cols-3 gap-4">
            <StatCard
              label="Funds freed 72h"
              value={p ? euro(p.funds_freed_eur72h) : '—'}
              accent={p && p.funds_freed_eur72h > 0 ? '#DB6D28' : undefined}
            />
            <StatCard label="Limit raised 72h" value={p ? euro(p.limit_raised_eur72h) : '—'} />
            <StatCard label="New payees 72h" value={p?.beneficiary_add_count72h ?? '—'} />
          </div>

          {p && p.funds_freed_eur72h > 0 && (
            <div className="flex items-center gap-2.5 px-3.5 py-2.5 rounded-lg bg-hold/10 border border-hold/30">
              <Snowflake size={15} className="text-hold" />
              <span className="text-[12.5px] text-fg">
                Active funds-freed window — the engine factors this posture into any outbound payment it scores from this account.
              </span>
            </div>
          )}

          <Panel title="Observations — devices · networks · geo">
            {a && a.observations.length > 0 ? (
              <div className="space-y-1.5">
                {a.observations.map((o, i) => {
                  const Icon = OBS_ICON[o.kind] ?? Globe;
                  return (
                    <div key={i} className="flex items-center gap-3 py-1.5 border-b border-line/50 last:border-0">
                      <Icon size={14} className="text-cyan" />
                      <span className="font-mono text-[10px] text-muted uppercase w-16">{o.kind}</span>
                      <Mono className="text-[12px] text-fg flex-1 truncate">{o.value}</Mono>
                      <span className="text-[11px] text-muted">first seen {timeAgo(o.first_seen_at)}</span>
                    </div>
                  );
                })}
              </div>
            ) : (
              <NeedsReview label="no observations" />
            )}
          </Panel>
        </div>

        <div className="space-y-6">
          <Panel title="Kill-chain timeline">
            {a ? <KillChainTimeline account={a} /> : <div className="text-[12px] text-muted">Loading…</div>}
          </Panel>

          <Panel
            title="Decision history"
            right={history.length > 0 ? <span className="font-mono text-[10px] text-muted">{history.length} events</span> : null}
          >
            <table className="w-full text-[12px]">
              <tbody>
                {historyPage.map((e) => (
                  <tr key={e.event_id} className="border-b border-line/50 last:border-0">
                    <td className="py-2 pr-2 text-muted whitespace-nowrap">{clock(e.created_at)}</td>
                    <td className="py-2 pr-2 text-fg-2">{e.event_type ?? '—'}</td>
                    <td className="py-2 pr-2 font-mono text-fg text-right">{euro(e.amount_eur)}</td>
                    <td className="py-2 pr-2"><OutcomePill outcome={e.verdict} /></td>
                    <td className="py-2"><ScoreMeter score={e.score} /></td>
                  </tr>
                ))}
                {history.length === 0 && (
                  <tr><td className="py-6 text-center text-muted" colSpan={5}>No history.</td></tr>
                )}
              </tbody>
            </table>
            <Pagination page={safePage} pageSize={HISTORY_PAGE_SIZE} total={history.length} onPage={setPage} />
          </Panel>
        </div>
      </div>
    </div>
  );
}
