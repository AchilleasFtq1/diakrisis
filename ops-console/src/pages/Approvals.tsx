import { useMemo, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Check, Lock, X } from 'lucide-react';
import { api } from '../lib/api';
import { loadSession } from '../lib/auth';
import { PageHead } from '../components/Layout';
import { Panel, Pagination, SearchInput } from '../components/widgets';
import { Mono } from '../components/primitives';
import { euro, countdown } from '../lib/format';

const PAGE_SIZE = 8;

export default function Approvals() {
  const qc = useQueryClient();
  const me = loadSession()?.sub;
  const approvals = useQuery({ queryKey: ['approvals'], queryFn: api.approvals, refetchInterval: 5000 });

  const [query, setQuery] = useState('');
  const [mineOnly, setMineOnly] = useState(false);
  const [page, setPage] = useState(1);

  const act = useMutation({
    mutationFn: ({ id, kind }: { id: string; kind: 'approve' | 'reject' }) =>
      kind === 'approve' ? api.approve(id) : api.reject(id),
    onSettled: () => qc.invalidateQueries({ queryKey: ['approvals'] }),
  });

  const rows = approvals.data ?? [];
  const filtered = useMemo(() => {
    const q = query.toLowerCase();
    return rows.filter((a) => {
      if (mineOnly && a.initiator_user_id !== me) return false;
      if (!q) return true;
      return [a.event_id, a.initiator_user_id, a.state].filter(Boolean).join(' ').toLowerCase().includes(q);
    });
  }, [rows, query, mineOnly, me]);

  const pageCount = Math.max(1, Math.ceil(filtered.length / PAGE_SIZE));
  const safePage = Math.min(page, pageCount);
  const pageRows = filtered.slice((safePage - 1) * PAGE_SIZE, safePage * PAGE_SIZE);

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
                {filtered.length === rows.length ? `${rows.length} waiting` : `${filtered.length} of ${rows.length}`}
              </span>
            </div>
          }
        >
          <div className="space-y-3">
            {pageRows.map((a) => {
              const ownAction = me && me === a.initiator_user_id; // four-eyes: can't approve your own
              return (
                <div key={a.event_id} className="border border-line rounded-lg p-4 bg-panel-2">
                  <div className="flex items-center gap-3 flex-wrap">
                    <span className="font-mono text-[11px] text-approval bg-approval/12 border border-approval/30 rounded px-2 py-0.5">
                      {a.state}
                    </span>
                    <Mono className="text-[12px] text-fg">{euro(a.amount_eur)}</Mono>
                    <span className="text-[12px] text-fg-2">
                      initiated by <Mono className="text-fg">{a.initiator_user_id ?? '—'}</Mono>
                    </span>
                    <span className="font-mono text-[11px] text-hold ml-auto">
                      expires in {countdown(a.hold_expires_at)}
                    </span>
                  </div>
                  <Mono className="text-[10.5px] text-muted block mt-1.5">{a.event_id}</Mono>
                  {a.batch_held_item_ids && a.batch_held_item_ids.length > 0 && (
                    <div className="mt-2 text-[11px] text-fg-2">
                      held lines:{' '}
                      {a.batch_held_item_ids.map((b) => (
                        <span key={b} className="font-mono text-block">{b} </span>
                      ))}
                    </div>
                  )}

                  <div className="flex items-center gap-2 mt-3">
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
            {filtered.length === 0 && (
              <div className="py-10 text-center text-muted text-[13px]">
                {rows.length === 0 ? 'No actions awaiting approval.' : 'No actions match the current filters.'}
              </div>
            )}
          </div>
          <Pagination page={safePage} pageSize={PAGE_SIZE} total={filtered.length} onPage={setPage} />
        </Panel>
      </div>
    </div>
  );
}
