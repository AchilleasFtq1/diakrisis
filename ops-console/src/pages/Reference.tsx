import { useMemo, useState } from 'react';
import { PageHead } from '../components/Layout';
import { Panel, SearchInput } from '../components/widgets';
import { SIGNAL_FAMILIES, OUTCOME_BANDS, TYPOLOGIES } from '../lib/catalog';
import type { SignalDoc } from '../lib/catalog';

/** A signal id badge in its family colour. */
function SignalBadge({ id, hex }: { id: string; hex: string }) {
  return (
    <span
      className="inline-flex items-center justify-center font-mono text-[11px] font-semibold rounded px-1.5 py-0.5 border min-w-[34px]"
      style={{ color: hex, background: `${hex}1a`, borderColor: `${hex}55` }}
    >
      {id}
    </span>
  );
}

function SignalRow({ s, hex }: { s: SignalDoc; hex: string }) {
  return (
    <div className="grid grid-cols-[44px_minmax(150px,1fr)_2.2fr_120px] gap-3 items-start py-2.5 border-b border-line/50 last:border-0">
      <SignalBadge id={s.id} hex={hex} />
      <div>
        <div className="text-[12.5px] text-fg font-medium">{s.name}</div>
        <div className="font-mono text-[10px] text-muted mt-0.5">{s.firesOn}</div>
      </div>
      <div className="text-[12px] text-fg-2 leading-relaxed">{s.meaning}</div>
      <div className="text-right">
        <span
          className="font-mono text-[12px] font-semibold tabular-nums"
          style={{ color: s.weight < 0 ? '#3fb950' : '#e6edf3' }}
        >
          {s.weight < 0 ? '' : '+'}{s.weight}
        </span>
        <span className="font-mono text-[9px] text-muted ml-1">{s.cap ? 'wt·cap' : 'wt'}</span>
      </div>
    </div>
  );
}

export default function Reference() {
  const [query, setQuery] = useState('');
  const q = query.trim().toLowerCase();

  const filteredFamilies = useMemo(() => {
    if (!q) return SIGNAL_FAMILIES;
    return SIGNAL_FAMILIES.map((f) => ({
      ...f,
      signals: f.signals.filter((s) => `${s.id} ${s.name} ${s.meaning} ${s.firesOn}`.toLowerCase().includes(q)),
    })).filter((f) => f.signals.length > 0);
  }, [q]);

  const totalSignals = SIGNAL_FAMILIES.reduce((n, f) => n + f.signals.length, 0);
  const shown = filteredFamilies.reduce((n, f) => n + f.signals.length, 0);

  return (
    <div>
      <PageHead
        eyebrow="Reference"
        title="Signal catalog & glossary"
        right={<SearchInput value={query} onChange={setQuery} placeholder="search signals…" width="w-64" />}
      />

      <div className="px-8 py-6 space-y-6">
        <p className="text-[12.5px] text-fg-2 leading-relaxed max-w-3xl">
          Every decision is a weighted sum of these signals over the action, the account’s 72h posture and its
          observation history (score = Σ weight·value, clipped 0–100). Weights are hand-set for regulatory
          explainability and live-tunable per bank. Below is what each code on the decision page means.
        </p>

        {/* Outcomes & bands */}
        <Panel title="Outcomes — five graduated frictions (score bands)">
          <div className="space-y-2">
            {OUTCOME_BANDS.map((o) => (
              <div key={o.outcome} className="grid grid-cols-[150px_70px_1fr] gap-3 items-center py-1.5 border-b border-line/50 last:border-0">
                <span
                  className="font-mono text-[11px] font-semibold px-2 py-0.5 rounded border w-fit"
                  style={{ color: o.hex, background: `${o.hex}1a`, borderColor: `${o.hex}55` }}
                >
                  {o.outcome}
                </span>
                <span className="font-mono text-[11px] text-muted">{o.band}</span>
                <span className="text-[12px] text-fg-2">{o.friction}</span>
              </div>
            ))}
          </div>
          <p className="mt-3 text-[11px] text-muted">
            Instant / P2P rail shifts the band edges −8 (friction proportional to irreversibility). Non-monetary
            actions cap at CONFIRM. On a transfer ALLOW, the PSD2 TRA check sets <span className="font-mono text-cyan">sca_exempt</span> with a logged basis.
          </p>
        </Panel>

        {/* Signals */}
        <Panel
          title="Signals"
          right={<span className="font-mono text-[10.5px] text-muted">{q ? `${shown} of ${totalSignals}` : `${totalSignals} signals`}</span>}
        >
          <div className="space-y-5">
            {filteredFamilies.map((f) => (
              <div key={f.prefix}>
                <div className="flex items-baseline gap-2.5 mb-1.5">
                  <span className="font-mono text-[12px] font-semibold" style={{ color: f.hex }}>{f.prefix}</span>
                  <span className="text-[13px] font-semibold text-fg">{f.label}</span>
                  <span className="text-[11px] text-muted">— {f.blurb}</span>
                </div>
                <div>
                  {f.signals.map((s) => <SignalRow key={s.id} s={s} hex={f.hex} />)}
                </div>
              </div>
            ))}
            {filteredFamilies.length === 0 && (
              <div className="py-6 text-center text-muted text-[13px]">No signals match “{query}”.</div>
            )}
          </div>
          <p className="mt-4 text-[11px] text-muted">
            A negative weight (B4) is protective — it lowers the score. M1/M2 contributions are capped, and any of
            the ML signals going to zero leaves a fully-functional deterministic engine (resilience, not simplicity).
          </p>
        </Panel>

        {/* Typologies */}
        <Panel title="Typologies — named scam patterns (composites)">
          <p className="text-[11.5px] text-muted mb-3">
            A single match pins the outcome to HOLD; two or more (or raw score ≥ 90) escalate to BLOCK. Each names
            the script in the customer-facing explanation.
          </p>
          <div className="space-y-2.5">
            {TYPOLOGIES.map((t) => (
              <div key={t.key} className="border border-line rounded-lg p-3 bg-panel-2">
                <div className="flex items-center gap-2.5 flex-wrap">
                  <span className="font-mono text-[11px] text-block bg-block/10 border border-block/30 rounded px-2 py-0.5">{t.key}</span>
                  <span className="text-[12.5px] text-fg font-medium">{t.name}</span>
                  <span className="font-mono text-[10.5px] text-cyan ml-auto">{t.rule}</span>
                </div>
                <p className="text-[12px] text-fg-2 mt-2 leading-relaxed">{t.meaning}</p>
              </div>
            ))}
          </div>
        </Panel>
      </div>
    </div>
  );
}
