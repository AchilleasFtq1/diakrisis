import type { ComponentType } from 'react';
import { Link, useParams } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Ban, Check, Cpu, CornerUpRight, Globe, Lock, MonitorSmartphone, Network, Radio, Smartphone, X } from 'lucide-react';
import { api, type LifecycleAction } from '../lib/api';
import { ApiError } from '../lib/api';
import { loadSession } from '../lib/auth';
import { OutcomePill, NeedsReview, Mono, ColHint } from '../components/primitives';
import { KillChainTimeline } from '../components/KillChainTimeline';
import { euro, scoreColor } from '../lib/format';
import type { DecisionDetail as DecisionDetailType, Outcome, Signal } from '../lib/types';

type ActionDef = { kind: LifecycleAction; label: string; icon: ComponentType<{ size?: number }>; tone: 'ok' | 'danger' | 'neutral'; disabled?: boolean; hint?: string };

const TONE: Record<'ok' | 'danger' | 'neutral', string> = {
  ok: 'text-ink bg-allow hover:bg-allow/90',
  danger: 'text-block bg-block/10 border border-block/40 hover:bg-block/20',
  neutral: 'text-cyan bg-cyan/10 border border-cyan/40 hover:bg-cyan/20',
};

/**
 * Per-event request context, persisted on the decision at scoring time, paired with the signal each
 * value drives. Real per-transaction values (device, network, geo, channel) — not the account's
 * running observations. A null value (older decisions) renders the review flag, never a guess.
 */
const CONTEXT_FIELDS: {
  label: string;
  icon: ComponentType<{ size?: number; className?: string }>;
  key: keyof DecisionDetailType;
  signal?: string;
}[] = [
  { label: 'Device', icon: Smartphone, key: 'device_id', signal: 'D1' },
  { label: 'Platform', icon: MonitorSmartphone, key: 'device_platform', signal: 'D2' },
  { label: 'IP address', icon: Network, key: 'ip' },
  { label: 'Network', icon: Network, key: 'network', signal: 'G2' },
  { label: 'Location', icon: Globe, key: 'geo_country', signal: 'G1' },
  { label: 'Channel', icon: Radio, key: 'channel', signal: 'C1' },
];

/** Lifecycle state → severity token for the topbar chip. */
const STATE_COLOR: Record<string, string> = {
  HELD: '#DB6D28',
  PENDING_APPROVAL: '#A371F7',
  BLOCKED: '#F85149',
  CANCELLED: '#F85149',
  EXECUTED: '#3FB950',
  CONFIRMED: '#3FB950',
};

/** Per-signal severity, by its share of the biggest contribution on this decision. */
function signalColor(contribution: number, max: number): string {
  if (contribution < 0) return '#3FB950';
  const pct = max > 0 ? contribution / max : 0;
  if (pct >= 0.66) return '#F85149';
  if (pct >= 0.33) return '#DB6D28';
  if (pct > 0) return '#D29922';
  return '#93A1B0';
}

function SignalRow({ s, max }: { s: Signal; max: number }) {
  const color = signalColor(s.contribution, max);
  const pct = max > 0 ? Math.min(100, (Math.abs(s.contribution) / max) * 100) : 0;
  return (
    <div className="grid grid-cols-[minmax(140px,180px)_70px_1fr_48px] gap-2.5 items-center py-2 border-b border-[#14191f] last:border-0">
      <span className="font-mono text-[11px] text-fg truncate" title={s.id}>{s.id}</span>
      <span className="font-mono text-[11px] tabular-nums" style={{ color }}>{s.value.toFixed(2)}</span>
      <span className="flex items-center gap-2">
        <span className="font-mono text-[9.5px] text-muted w-9 shrink-0 tabular-nums">{s.weight.toFixed(0)}</span>
        <span className="flex-1 h-[7px] rounded bg-panel-2 overflow-hidden">
          <span className="block h-full rounded" style={{ width: `${pct}%`, background: color }} />
        </span>
      </span>
      <span className="font-mono text-[11.5px] text-right tabular-nums font-semibold" style={{ color }}>
        {s.contribution < 0 ? '' : '+'}{s.contribution.toFixed(0)}
      </span>
    </div>
  );
}

export default function DecisionDetail() {
  const { id = '' } = useParams();
  const decision = useQuery({ queryKey: ['decision', id], queryFn: () => api.decision(id) });

  const d = decision.data;
  const accountId = d?.account_id ?? undefined;
  const account = useQuery({
    queryKey: ['account', accountId],
    queryFn: () => api.account(accountId!),
    enabled: !!accountId,
  });

  const ev = d?.engine_verdict;
  const score = ev?.score ?? null;
  const verdict = (d?.combined?.decision ?? ev?.decision ?? null) as Outcome | null;
  const engineVerdict = (ev?.decision ?? null) as Outcome | null;
  const lifecycleState = d?.lifecycle_state ?? d?.lifecycle?.state ?? null;

  const signals = ev?.signals ? [...ev.signals].sort((a, b) => Math.abs(b.contribution) - Math.abs(a.contribution)) : [];
  const material = signals.filter((s) => s.contribution !== 0);
  const maxContribution = signals.reduce((m, s) => Math.max(m, Math.abs(s.contribution)), 1);
  const signalById = new Map(signals.map((s) => [s.id, s]));
  const hasContext = d != null && CONTEXT_FIELDS.some((f) => d[f.key] != null);
  const beneficiary = d?.counterparty_name ?? d?.counterparty_ref ?? null;

  const typologies = ev?.typologies ?? [];
  const ai = d?.ai_co_judge;
  const aiUnavailable = !ai || ai.status === 'UNAVAILABLE' || (!ai.decision && !ai.score);
  const concur = ai?.agreement === 'CONCUR';

  const fundsFreed = account.data?.posture?.funds_freed_eur72h ?? 0;
  // Escalated treatment only when the engine actually fired a chain typology — no client-side guessing.
  const isKillChain = typologies.some((t) => t.toLowerCase().includes('chain'));

  const latencyOk = d != null && d.latency_ms < 50;
  const stamp = d?.created_at ? new Date(d.created_at).toLocaleString('en-GB', { hour12: false }) : '—';
  const batchItems = d?.items ?? [];

  // --- lifecycle actions an analyst can drive from here ---
  const qc = useQueryClient();
  const session = loadSession();
  const canApprove = session?.roles?.some((r) => r === 'APPROVER' || r === 'ADMIN') ?? false;
  const isInitiator = !!session?.sub && session.sub === d?.initiator_sub;
  const holdLocked = d?.hold_expires_at ? new Date(d.hold_expires_at).getTime() > Date.now() : false;

  const act = useMutation({
    mutationFn: (action: LifecycleAction) => api.act(id, action),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['decision', id] });
      qc.invalidateQueries({ queryKey: ['feed'] });
      qc.invalidateQueries({ queryKey: ['approvals'] });
      qc.invalidateQueries({ queryKey: ['account'] });
    },
  });

  const actions: ActionDef[] =
    lifecycleState === 'HELD'
      ? [
          { kind: 'release', label: holdLocked ? 'Release (locked until hold expiry)' : 'Release — execute', icon: holdLocked ? Lock : CornerUpRight, tone: 'neutral', disabled: holdLocked, hint: holdLocked ? 'the cooling-off hold cannot be skipped early' : 'records a false positive' },
          { kind: 'cancel', label: 'Cancel — block', icon: Ban, tone: 'danger', hint: 'records a confirmed save' },
        ]
      : lifecycleState === 'PENDING_APPROVAL' || lifecycleState === 'REVIEW'
        ? [
            { kind: 'approve', label: 'Approve', icon: Check, tone: 'ok', disabled: !canApprove || isInitiator, hint: isInitiator ? "four-eyes: can't approve your own action" : !canApprove ? 'requires APPROVER or ADMIN' : undefined },
            { kind: 'reject', label: 'Reject', icon: X, tone: 'danger', disabled: !canApprove, hint: !canApprove ? 'requires APPROVER or ADMIN' : undefined },
          ]
        : [];

  // A 409 means different things per action: for approve/reject it is a four-eyes race (another
  // authoriser already resolved it); for release it is the cooling-off hold still being locked. Key the
  // message off the action actually attempted (act.variables) rather than assuming it was a release.
  const lastAction = act.variables as LifecycleAction | undefined;
  const actionError =
    act.error instanceof ApiError
      ? act.error.status === 409
        ? lastAction === 'approve' || lastAction === 'reject'
          ? 'This action was already resolved by another approver.'
          : 'Hold is still locked until its expiry — it cannot be released early.'
        : act.error.status === 403
          ? 'Not permitted (four-eyes self-approval, or APPROVER role required).'
          : `Action failed (${act.error.status}).`
      : act.error
        ? 'Action failed.'
        : null;

  // Distinguish "still loading" and "not found / failed" from a real decision that simply has sparse
  // data — otherwise a bad/expired/typo'd id renders a degraded shell of '—' placeholders with no way
  // to tell it apart from a genuine decision. (All hooks above run unconditionally; these early returns
  // come after them so the rules-of-hooks order is preserved.)
  if (decision.isLoading) {
    return <div className="px-8 py-10 text-muted font-mono text-[13px]">Loading decision…</div>;
  }
  if (decision.isError || !d) {
    const notFound = decision.error instanceof ApiError && decision.error.status === 404;
    return (
      <div className="px-8 py-8">
        <Link to="/overview" className="font-mono text-[11.5px] text-muted hover:text-fg">← Live feed</Link>
        <div className="mt-6 text-fg text-[15px] font-semibold">
          {notFound ? 'Decision not found' : 'Failed to load decision'}
        </div>
        <div className="mt-1 text-muted text-[13px]">
          {notFound
            ? <>No decision exists for id <Mono className="text-fg-2">{id}</Mono>.</>
            : 'The decision could not be loaded. Please try again.'}
        </div>
      </div>
    );
  }

  return (
    <div>
      {/* topbar */}
      <div className="min-h-[56px] border-b border-line flex items-center justify-between px-6 py-2.5 gap-4 flex-wrap bg-ink">
        <div className="flex items-center gap-3.5 flex-wrap">
          <Link to="/overview" className="font-mono text-[11.5px] text-muted hover:text-fg">← Live feed</Link>
          <span className="w-px h-4 bg-line" />
          <Mono className="text-[14px] font-semibold text-fg">{id}</Mono>
          <OutcomePill outcome={verdict} />
          {lifecycleState && (
            <span
              className="font-mono text-[10px] font-semibold px-2 py-0.5 rounded border"
              style={{
                color: STATE_COLOR[lifecycleState] ?? '#93A1B0',
                background: `${STATE_COLOR[lifecycleState] ?? '#93A1B0'}1a`,
                borderColor: `${STATE_COLOR[lifecycleState] ?? '#93A1B0'}4d`,
              }}
            >
              {lifecycleState}
            </span>
          )}
        </div>
        <div className="font-mono text-[11px] text-muted">
          {accountId ? `account ${accountId}` : 'account —'} · {d?.initiator_sub ?? '—'} · {stamp}
        </div>
      </div>

      {/* action bar — drive the lifecycle from the console */}
      {actions.length > 0 && (
        <div className="border-b border-line bg-panel/60 px-6 py-2.5 flex items-center gap-3 flex-wrap">
          <span className="font-mono text-[10px] uppercase tracking-wide text-muted">Actions</span>
          {actions.map((a) => (
            <button
              key={a.kind}
              onClick={() => act.mutate(a.kind)}
              disabled={a.disabled || act.isPending}
              title={a.hint}
              className={`flex items-center gap-1.5 text-[12px] font-semibold rounded px-3 py-1.5 transition-colors disabled:opacity-45 disabled:cursor-not-allowed ${TONE[a.tone]}`}
            >
              <a.icon size={13} /> {a.label}
            </button>
          ))}
          {actions.some((a) => a.hint && !a.disabled) && (
            <span className="font-mono text-[10.5px] text-muted">
              {actions.find((a) => a.hint && !a.disabled)?.hint}
            </span>
          )}
          {actionError && <span className="font-mono text-[11px] text-block ml-auto">{actionError}</span>}
          {act.isSuccess && !act.isPending && (
            <span className="font-mono text-[11px] text-allow ml-auto">Done — lifecycle updated.</span>
          )}
        </div>
      )}

      <div className="px-6 py-5 flex flex-col gap-4">
        {/* KILL-CHAIN HERO — the hero of this screen */}
        <div
          className="rounded-xl p-5"
          style={
            isKillChain
              ? { background: 'linear-gradient(180deg,#1a1014,#141A21 60%)', border: '1px solid rgba(248,81,73,.4)' }
              : { background: '#141A21', border: '1px solid #232B36' }
          }
        >
          <div className="flex items-center justify-between gap-4 flex-wrap mb-4">
            <div className="flex items-center gap-2.5 flex-wrap">
              {isKillChain ? (
                <span className="font-mono text-[11px] font-semibold tracking-[0.1em] text-block bg-block/12 border border-block/40 px-2.5 py-1 rounded-md">
                  ⛓ KILL-CHAIN DETECTED
                </span>
              ) : (
                <span className="font-mono text-[11px] font-semibold tracking-[0.1em] text-cyan bg-cyan/10 border border-cyan/30 px-2.5 py-1 rounded-md">
                  RECENT ACCOUNT ACTIVITY
                </span>
              )}
              {typologies[0] && <span className="font-mono text-[12px] text-fg">{typologies[0]}</span>}
            </div>
            <span className="text-[12px] text-fg-2">
              {isKillChain ? (
                <>The engine scored the <span className="text-block font-semibold">sequence</span>, not just this payment — earlier steps built the posture behind this verdict.</>
              ) : (
                'The actions the engine remembered on this account, oldest → newest.'
              )}
            </span>
          </div>

          {fundsFreed > 0 && (
            <div className="flex items-center gap-2.5 mb-4 px-3.5 py-2 rounded-lg bg-hold/10 border border-hold/30">
              <span className="text-hold">❄</span>
              <span className="text-[12px] text-fg">
                <span className="font-mono text-hold font-semibold">{euro(fundsFreed)}</span> freed in the last 72h —
                the posture the engine remembers when scoring outbound payments from this account.
              </span>
            </div>
          )}

          {account.data ? (
            <KillChainTimeline account={account.data} highlightEventId={id} anchorTime={d.created_at} isKillChain={isKillChain} />
          ) : accountId ? (
            <div className="text-[12px] text-muted">Loading account history…</div>
          ) : (
            <NeedsReview label="account history unavailable" />
          )}
        </div>

        {/* ROW: score + verdict | signals */}
        <div className="grid grid-cols-1 xl:grid-cols-[300px_1fr] gap-4">
          <div className="flex flex-col gap-4">
            {/* risk score */}
            <div className="bg-panel border border-line rounded-xl p-5">
              <div className="font-mono text-[10px] tracking-[0.1em] uppercase text-muted">Risk score</div>
              {score != null ? (
                <>
                  <div className="flex items-baseline gap-2 mt-2.5">
                    <span className="font-mono text-[46px] font-semibold leading-none" style={{ color: scoreColor(score) }}>
                      {Math.round(score)}
                    </span>
                    <span className="font-mono text-[16px] text-muted">/ 100</span>
                  </div>
                  <div className="h-[9px] rounded bg-panel-2 border border-line overflow-hidden mt-3.5">
                    <div className="h-full severity-fill" style={{ width: `${Math.min(100, Math.max(0, Math.round(score)))}%` }} />
                  </div>
                  {/* Ticks mark the authoritative band edges (CONFIRM 30 · HOLD 60 · BLOCK 85) so the bar's colour reads against them. */}
                  <div className="flex justify-between font-mono text-[9px] text-muted mt-1.5">
                    <span>0</span><span>30</span><span>60</span><span>85</span>
                  </div>
                </>
              ) : (
                <div className="mt-3"><NeedsReview label="no score" /></div>
              )}
            </div>

            {/* verdict */}
            <div className="bg-panel border border-line rounded-xl p-5">
              <div className="font-mono text-[10px] tracking-[0.1em] uppercase text-muted mb-3">
                Verdict · engine vs AI co-judge
              </div>
              <div className="flex items-center justify-between py-2 border-b border-[#1c232c]">
                <span className="text-[12px] text-fg-2">Engine</span>
                <OutcomePill outcome={engineVerdict} />
              </div>
              <div className="flex items-center justify-between py-2 border-b border-[#1c232c]">
                <span className="flex items-center gap-1.5 text-[12px] text-fg-2"><Cpu size={13} className="text-cyan" /> AI co-judge · Gemma</span>
                {aiUnavailable ? <NeedsReview label="UNAVAILABLE" /> : <OutcomePill outcome={(ai?.decision as Outcome) ?? null} />}
              </div>
              <div className="flex items-center justify-between py-2">
                <span className="text-[12px] text-fg-2">Combined</span>
                {aiUnavailable ? (
                  <OutcomePill outcome={verdict} />
                ) : (
                  <span
                    className="font-mono text-[11px] font-semibold"
                    style={{ color: concur ? '#3FB950' : '#D29922' }}
                  >
                    {concur ? '✓ agree' : '⚠ diverge'}
                  </span>
                )}
              </div>
              <div className="flex items-center justify-between mt-2.5 pt-3 border-t border-[#1c232c]">
                <span className="font-mono text-[11px] text-muted">latency</span>
                <span className="flex items-center gap-1.5 font-mono text-[11px] text-fg">
                  {d ? `${d.latency_ms}ms` : '—'}
                  {d != null && (
                    <span
                      className="font-mono text-[9.5px] px-1.5 py-px rounded border"
                      style={
                        latencyOk
                          ? { color: '#3FB950', background: 'rgba(63,185,80,.1)', borderColor: 'rgba(63,185,80,.3)' }
                          : { color: '#D29922', background: 'rgba(210,153,34,.1)', borderColor: 'rgba(210,153,34,.3)' }
                      }
                    >
                      {latencyOk ? '✓ SLA' : 'over SLA'}
                    </span>
                  )}
                </span>
              </div>
            </div>

            {/* session context — real per-event device · network · geo · channel */}
            <div className="bg-panel border border-line rounded-xl p-5">
              <div className="font-mono text-[10px] tracking-[0.1em] uppercase text-muted mb-3">
                Session context
              </div>
              {!d ? (
                <div className="text-[12px] text-muted">Loading…</div>
              ) : hasContext ? (
                <div className="space-y-2.5">
                  {CONTEXT_FIELDS.map((f) => {
                    const value = d[f.key] as string | null | undefined;
                    const Icon = f.icon;
                    const sig = f.signal ? signalById.get(f.signal) : undefined;
                    const fired = sig != null && sig.contribution !== 0;
                    return (
                      <div key={f.key} className="flex items-center gap-2.5">
                        <Icon size={13} className="text-cyan shrink-0" />
                        <div className="min-w-0 flex-1">
                          <div className="font-mono text-[9px] uppercase tracking-wide text-muted">{f.label}</div>
                          {value != null ? (
                            <Mono className="text-[12px] text-fg block truncate">{value}</Mono>
                          ) : (
                            <NeedsReview label="not captured" />
                          )}
                        </div>
                        {fired && (
                          <span
                            className="font-mono text-[10px] px-1.5 py-0.5 rounded border border-block/30 bg-block/10 text-block shrink-0"
                            title={`signal ${sig!.id} contribution`}
                          >
                            {sig!.id} +{sig!.contribution.toFixed(0)}
                          </span>
                        )}
                      </div>
                    );
                  })}
                  {beneficiary && (
                    <div className="flex items-start gap-2.5 pt-2 border-t border-[#1c232c]">
                      <Globe size={13} className="text-cyan mt-0.5 shrink-0" />
                      <div className="min-w-0 flex-1">
                        <div className="font-mono text-[9px] uppercase tracking-wide text-muted">
                          Beneficiary{d.counterparty_addressing ? ` · ${d.counterparty_addressing}` : ''}
                          {d.rail ? ` · ${d.rail}` : ''}
                        </div>
                        <Mono className="text-[12px] text-fg block truncate">{beneficiary}</Mono>
                        {d.counterparty_name && d.counterparty_ref && (
                          <Mono className="text-[10.5px] text-muted block truncate">{d.counterparty_ref}</Mono>
                        )}
                      </div>
                    </div>
                  )}
                </div>
              ) : (
                <div>
                  <NeedsReview label="context not captured" />
                  <p className="mt-2 text-[10.5px] text-muted">Decision predates per-event context capture.</p>
                </div>
              )}
            </div>
          </div>

          {/* contributing signals */}
          <div className="bg-panel border border-line rounded-xl p-5">
            <div className="flex items-center justify-between mb-1">
              <div className="text-[13px] font-semibold text-fg">Contributing signals</div>
              {signals.length > 0 ? (
                <div className="font-mono text-[10px] text-muted">
                  {signals.length} evaluated · {material.length} material{score != null ? ` · Σ = ${score}` : ''}
                </div>
              ) : null}
            </div>
            {signals.length > 0 ? (
              <>
                <div className="grid grid-cols-[minmax(140px,180px)_70px_1fr_48px] gap-2.5 font-mono text-[9px] tracking-[0.06em] uppercase text-muted py-2.5 border-b border-[#1c232c]">
                  <span>signal id<ColHint text="The signal code. See the Reference page for what every code means." /></span><span>value<ColHint text="The signal's raw value in 0–1 (how strongly it fired)." /></span><span>weight × contribution<ColHint text="The signal's weight, and its bar = its points contributed to the score." /></span><span className="text-right">+pts<ColHint text="Points this signal added to (or, if negative, removed from) the risk score." /></span>
                </div>
                {material.map((s) => <SignalRow key={s.id} s={s} max={maxContribution} />)}
              </>
            ) : (
              <div className="mt-3"><NeedsReview label="no signal breakdown" /></div>
            )}
          </div>
        </div>

        {/* BATCH LINE BREAKDOWN — per-line results for a mass payment */}
        {batchItems.length > 0 && (
          <div className="bg-panel border border-line rounded-xl p-5">
            <div className="flex items-center justify-between mb-3">
              <div className="text-[13px] font-semibold text-fg">Batch lines</div>
              <div className="font-mono text-[10px] text-muted">
                {batchItems.length} lines · {batchItems.filter((it) => it.decision !== 'ALLOW').length} flagged
              </div>
            </div>
            <div className="overflow-x-auto">
              <table className="w-full text-[12px]">
                <thead>
                  <tr className="text-left font-mono text-[9px] tracking-[0.06em] uppercase text-muted border-b border-[#1c232c]">
                    <th className="py-2 pr-3 font-medium">Line<ColHint text="The batch line item id." /></th>
                    <th className="py-2 pr-3 font-medium">Outcome<ColHint text="Per-line verdict — flagged lines are quarantined (HELD) while clean lines proceed." /></th>
                    <th className="py-2 pr-3 font-medium">Top signals<ColHint text="The signals that drove this line's verdict, biggest contribution first." /></th>
                  </tr>
                </thead>
                <tbody>
                  {batchItems.map((it) => {
                    const top = (it.signals ?? [])
                      .filter((s) => s.contribution !== 0)
                      .sort((a, b) => Math.abs(b.contribution) - Math.abs(a.contribution))
                      .slice(0, 4);
                    return (
                      <tr key={it.item_id} className="border-b border-line/50 last:border-0">
                        <td className="py-2 pr-3"><Mono className="text-[11px] text-fg-2">{it.item_id}</Mono></td>
                        <td className="py-2 pr-3"><OutcomePill outcome={it.decision as Outcome} /></td>
                        <td className="py-2 pr-3">
                          {top.length > 0 ? (
                            <div className="flex gap-1 flex-wrap">
                              {top.map((s) => (
                                <span key={s.id} className="font-mono text-[10px] text-fg-2 bg-panel-2 border border-line rounded px-1.5 py-0.5">
                                  {s.id} +{s.contribution.toFixed(0)}
                                </span>
                              ))}
                            </div>
                          ) : (
                            <span className="text-muted">—</span>
                          )}
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
          </div>
        )}

        {/* ROW: typologies | customer explanation */}
        <div className="grid grid-cols-1 xl:grid-cols-2 gap-4">
          <div className="bg-panel border border-line rounded-xl p-5">
            <div className="text-[13px] font-semibold text-fg mb-3">Typologies fired</div>
            {typologies.length > 0 ? (
              <div className="flex flex-wrap gap-2">
                {typologies.map((t, i) => (
                  <span
                    key={t}
                    className="font-mono text-[11px] text-block bg-block/10 border border-block/40 px-2.5 py-1 rounded-md"
                  >
                    {t}{i === 0 && typologies.length > 1 && <span className="text-fg-2 text-[9px] ml-1">· primary</span>}
                  </span>
                ))}
              </div>
            ) : (
              <p className="text-[12px] text-muted">No typology fired — scored on signals alone.</p>
            )}
            {d?.reason_code && (
              <div className="mt-3.5 font-mono text-[10.5px] text-cyan bg-cyan/10 border border-cyan/25 rounded px-2 py-1 inline-block">
                {d.reason_code}
              </div>
            )}
          </div>

          <div className="bg-panel border border-line rounded-xl p-5">
            <div className="flex items-center gap-2.5 mb-3">
              <span className="text-[13px] font-semibold text-fg">Customer-facing explanation</span>
              <span className="font-mono text-[9px] text-cyan bg-cyan/10 border border-cyan/30 px-1.5 py-0.5 rounded">as shown in app</span>
            </div>
            {d?.explanation?.customer ? (
              <div className="bg-ink border border-[#243042] rounded-lg p-4 text-[13px] text-fg leading-relaxed">
                “{d.explanation.customer}”
              </div>
            ) : (
              <div className="bg-ink border border-[#243042] rounded-lg p-4 text-[12.5px] text-muted leading-relaxed">
                Silent — nothing shown to the customer (clean ALLOW).
              </div>
            )}
            {!aiUnavailable && ai?.reason && (
              <p className="mt-3 text-[12px] text-fg-2 leading-relaxed">
                <span className="text-cyan">AI co-judge:</span> “{ai.reason}”
              </p>
            )}
            {d?.explanation?.audit && <p className="mt-2 font-mono text-[10.5px] text-muted">{d.explanation.audit}</p>}
          </div>
        </div>
      </div>
    </div>
  );
}
