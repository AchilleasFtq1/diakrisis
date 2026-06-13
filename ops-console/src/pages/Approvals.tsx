import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Check, Lock, X } from 'lucide-react';
import { api } from '../lib/api';
import { loadSession } from '../lib/auth';
import { PageHead } from '../components/Layout';
import { Panel } from '../components/widgets';
import { Mono } from '../components/primitives';
import { euro, countdown } from '../lib/format';

export default function Approvals() {
  const qc = useQueryClient();
  const me = loadSession()?.sub;
  const approvals = useQuery({ queryKey: ['approvals'], queryFn: api.approvals, refetchInterval: 5000 });

  const act = useMutation({
    mutationFn: ({ id, kind }: { id: string; kind: 'approve' | 'reject' }) =>
      kind === 'approve' ? api.approve(id) : api.reject(id),
    onSettled: () => qc.invalidateQueries({ queryKey: ['approvals'] }),
  });

  return (
    <div>
      <PageHead eyebrow="Four-eyes" title="Approval queue" />
      <div className="px-8 py-6">
        <Panel title="Actions awaiting a second authoriser">
          <div className="space-y-3">
            {approvals.data?.map((a) => {
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
            {approvals.data?.length === 0 && (
              <div className="py-10 text-center text-muted text-[13px]">No actions awaiting approval.</div>
            )}
          </div>
        </Panel>
      </div>
    </div>
  );
}
