import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { ArrowRight, Check, Lock, X } from 'lucide-react';
import { api } from '../lib/api';
import { loadSession } from '../lib/auth';
import { PageHead } from '../components/Layout';
import { Panel, Pagination, SearchInput } from '../components/widgets';
import { Mono } from '../components/primitives';
import { euro, countdown } from '../lib/format';

const PAGE_SIZE = 8;

export default function Approvals() {
  const qc = useQueryClient();
  const navigate = useNavigate();
  const me = loadSession()?.sub;

  const [query, setQuery] = useState('');
  const [mineOnly, setMineOnly] = useState(false);
  const [page, setPage] = useState(1);

  // Server-paged + server-filtered: search, "initiated by me" and page are query params.
  const approvals = useQuery({
    queryKey: ['approvals', page, query, mineOnly, me],
    queryFn: () => api.approvals({ page, size: PAGE_SIZE, q: query, initiator: mineOnly ? me : undefined }),
    refetchInterval: 5000,
    placeholderData: keepPreviousData,
  });

  const act = useMutation({
    mutationFn: ({ id, kind }: { id: string; kind: 'approve' | 'reject' }) =>
      kind === 'approve' ? api.approve(id) : api.reject(id),
    onSettled: () => qc.invalidateQueries({ queryKey: ['approvals'] }),
  });

  const pageRows = approvals.data?.items ?? [];
  const total = approvals.data?.total ?? 0;
  const filtersActive = mineOnly || query.length > 0;

  return (
    <div>
      <PageHead
        eyebrow="Four-eyes"
        title="Approval queue"
        right={
          <SearchInput
            value={query}
            onChange={(v) => {
              setQuery(v);
              setPage(1);
            }}
            placeholder="initiator · event id…"
          />
        }
      />
      <div className="px-8 py-6">
        <Panel
          title="Actions awaiting a second authoriser"
          right={
            <div className="flex items-center gap-2 font-mono text-[10.5px]">
              <button
                onClick={() => {
                  setMineOnly((v) => !v);
                  setPage(1);
                }}
                className="px-2 py-1 rounded border transition-colors"
                style={
                  mineOnly
                    ? { color: '#A371F7', background: 'rgba(163,113,247,.12)', borderColor: 'rgba(163,113,247,.4)' }
                    : { color: '#5C6773', background: 'transparent', borderColor: '#232B36' }
                }
              >
                initiated by me
              </button>
              <span className="text-muted">
                {filtersActive ? `${total} matching` : `${total} waiting`}
              </span>
            </div>
          }
        >
          {total > PAGE_SIZE && (
            <div className="border-b border-line/70 mb-3 -mt-1">
              <Pagination page={approvals.data?.page ?? page} pageSize={PAGE_SIZE} total={total} onPage={setPage} />
            </div>
          )}
          <div className="space-y-3">
            {pageRows.map((a) => {
              const ownAction = me && me === a.initiator_user_id; // four-eyes: can't approve your own
              const remaining = countdown(a.hold_expires_at);
              const lapsed = remaining === 'expired' || a.hold_expires_at == null;
              return (
                <div
                  key={a.event_id}
                  onClick={() => navigate(`/decisions/${encodeURIComponent(a.event_id)}`)}
                  className="group border border-line rounded-lg p-4 bg-panel-2 cursor-pointer hover:border-cyan/50 hover:bg-panel-2/60 transition-colors"
                >
                  <div className="flex items-center gap-3 flex-wrap">
                    <span className="font-mono text-[11px] text-approval bg-approval/12 border border-approval/30 rounded px-2 py-0.5">
                      {a.state}
                    </span>
                    <Mono className="text-[12px] text-fg">{euro(a.amount_eur)}</Mono>
                    <span className="text-[12px] text-fg-2">
                      initiated by <Mono className="text-fg">{a.initiator_user_id ?? '—'}</Mono>
                    </span>
                    {lapsed ? (
                      <span className="font-mono text-[11px] text-muted ml-auto">hold lapsed</span>
                    ) : (
                      <span className="font-mono text-[11px] text-hold ml-auto">expires in {remaining}</span>
                    )}
                  </div>
                  <div className="flex items-center gap-2 mt-1.5">
                    <Mono className="text-[10.5px] text-muted">{a.event_id}</Mono>
                    <span className="flex items-center gap-1 font-mono text-[10px] text-cyan opacity-0 group-hover:opacity-100 transition-opacity">
                      view decision <ArrowRight size={11} />
                    </span>
                  </div>
                  {a.batch_held_item_ids && a.batch_held_item_ids.length > 0 && (
                    <div className="mt-2 text-[11px] text-fg-2">
                      held lines:{' '}
                      {a.batch_held_item_ids.map((b) => (
                        <span key={b} className="font-mono text-block">{b} </span>
                      ))}
                    </div>
                  )}

                  <div className="flex items-center gap-2 mt-3" onClick={(e) => e.stopPropagation()}>
                    {ownAction ? (
                      <span className="flex items-center gap-1.5 font-mono text-[11px] text-muted bg-ink border border-line rounded px-3 py-1.5">
                        <Lock size={12} /> can't approve your own action (four-eyes)
                      </span>
                    ) : (
                      <>
                        <button
                          onClick={() => act.mutate({ id: a.event_id, kind: 'approve' })}
                          disabled={act.isPending}
                          className="flex items-center gap-1.5 text-[12px] font-semibold text-ink bg-allow hover:bg-allow/90 rounded px-3.5 py-1.5 disabled:opacity-60"
                        >
                          <Check size={13} /> Approve
                        </button>
                        <button
                          onClick={() => act.mutate({ id: a.event_id, kind: 'reject' })}
                          disabled={act.isPending}
                          className="flex items-center gap-1.5 text-[12px] font-semibold text-block bg-block/10 border border-block/40 hover:bg-block/20 rounded px-3.5 py-1.5 disabled:opacity-60"
                        >
                          <X size={13} /> Reject
                        </button>
                      </>
                    )}
                  </div>
                </div>
              );
            })}
            {pageRows.length === 0 && (
              <div className="py-10 text-center text-muted text-[13px]">
                {filtersActive ? 'No actions match the current filters.' : 'No actions awaiting approval.'}
              </div>
            )}
          </div>
          <Pagination page={approvals.data?.page ?? page} pageSize={PAGE_SIZE} total={total} onPage={setPage} />
        </Panel>
      </div>
    </div>
  );
}
