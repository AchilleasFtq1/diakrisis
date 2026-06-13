import { useState } from 'react';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { Network, AlertTriangle } from 'lucide-react';
import { api } from '../lib/api';
import { PageHead } from '../components/Layout';
import { Panel, Pagination, SearchInput } from '../components/widgets';
import { Mono } from '../components/primitives';
import { euro, timeAgo } from '../lib/format';

const PAGE_SIZE = 15;

/** Worst-outcome colour — the most severe verdict this beneficiary has ever attracted. */
const OUTCOME_HEX: Record<string, string> = {
  CONFIRMED_SAVE: '#f85149',
  BLOCK: '#f85149',
  HOLD: '#db6d28',
  REQUIRE_APPROVAL: '#a371f7',
  FALSE_POSITIVE: '#3fb950',
};

export default function Beneficiaries() {
  const [query, setQuery] = useState('');
  const [page, setPage] = useState(1);

  const cps = useQuery({
    queryKey: ['counterparties', page, query],
    queryFn: () => api.counterparties({ page, size: PAGE_SIZE, q: query }),
    refetchInterval: 8000,
    placeholderData: keepPreviousData,
  });

  const rows = cps.data?.items ?? [];
  const total = cps.data?.total ?? 0;

  return (
    <div>
      <PageHead
        eyebrow="Mule intelligence"
        title="Flagged beneficiaries"
        right={
          <SearchInput
            value={query}
            onChange={(v) => {
              setQuery(v);
              setPage(1);
            }}
            placeholder="name · IBAN · key…"
          />
        }
      />
      <div className="px-8 py-6">
        <Panel
          title="Counterparties the engine has flagged"
          right={<span className="font-mono text-[10.5px] text-muted">{total} flagged</span>}
        >
          <p className="text-[11.5px] text-muted mb-3 leading-relaxed">
            Fan-in is the count of distinct accounts that have paid this beneficiary — a high fan-in onto a
            freshly-seen payee is the <span className="text-block font-mono">mule_fan_out</span> tell.
          </p>
          {total > PAGE_SIZE && (
            <div className="border-b border-line/70 mb-2 -mt-1">
              <Pagination page={cps.data?.page ?? page} pageSize={PAGE_SIZE} total={total} onPage={setPage} />
            </div>
          )}
          <div className="overflow-x-auto">
            <table className="w-full text-[12.5px]">
              <thead>
                <tr className="text-left font-mono text-[10px] tracking-[0.08em] uppercase text-muted border-b border-line">
                  <th className="py-2 pr-3 font-medium">Beneficiary</th>
                  <th className="py-2 pr-3 font-medium">IBAN / key</th>
                  <th className="py-2 pr-3 font-medium text-right">Fan-in</th>
                  <th className="py-2 pr-3 font-medium text-right">Flags</th>
                  <th className="py-2 pr-3 font-medium">Worst outcome</th>
                  <th className="py-2 pr-3 font-medium text-right">Pays</th>
                  <th className="py-2 pr-3 font-medium text-right">Mean</th>
                  <th className="py-2 pr-3 font-medium">Last flagged</th>
                </tr>
              </thead>
              <tbody>
                {rows.map((c) => {
                  const hex = c.worst_outcome ? OUTCOME_HEX[c.worst_outcome] ?? '#93a1b0' : '#93a1b0';
                  const highFanIn = c.fan_in_accounts >= 3;
                  return (
                    <tr key={c.counterparty_key} className="border-b border-line/60">
                      <td className="py-2.5 pr-3 text-fg">{c.name ?? <span className="text-muted">— unnamed —</span>}</td>
                      <td className="py-2.5 pr-3">
                        <Mono className="text-[11px] text-fg-2">{c.iban ?? c.counterparty_key}</Mono>
                      </td>
                      <td className="py-2.5 pr-3 text-right">
                        <span className={`inline-flex items-center gap-1 font-mono tabular-nums ${highFanIn ? 'text-block font-semibold' : 'text-fg-2'}`}>
                          {highFanIn && <Network size={12} />}
                          {c.fan_in_accounts}
                        </span>
                      </td>
                      <td className="py-2.5 pr-3 text-right font-mono text-fg tabular-nums">{c.flag_count}</td>
                      <td className="py-2.5 pr-3">
                        {c.worst_outcome ? (
                          <span
                            className="font-mono text-[10.5px] px-1.5 py-0.5 rounded border"
                            style={{ color: hex, background: `${hex}1a`, borderColor: `${hex}55` }}
                          >
                            {c.worst_outcome}
                          </span>
                        ) : (
                          <span className="text-muted">—</span>
                        )}
                      </td>
                      <td className="py-2.5 pr-3 text-right font-mono text-fg-2 tabular-nums">{c.pay_count || '—'}</td>
                      <td className="py-2.5 pr-3 text-right font-mono text-fg-2 tabular-nums">
                        {c.mean_amount_eur != null ? euro(c.mean_amount_eur) : '—'}
                      </td>
                      <td className="py-2.5 pr-3 text-muted whitespace-nowrap">{timeAgo(c.last_flagged_at)}</td>
                    </tr>
                  );
                })}
                {rows.length === 0 && (
                  <tr>
                    <td colSpan={8} className="py-8 text-center text-muted">
                      {query ? 'No beneficiaries match.' : (
                        <span className="inline-flex items-center gap-2">
                          <AlertTriangle size={14} className="text-muted" /> No flagged beneficiaries yet.
                        </span>
                      )}
                    </td>
                  </tr>
                )}
              </tbody>
            </table>
          </div>
          <Pagination page={cps.data?.page ?? page} pageSize={PAGE_SIZE} total={total} onPage={setPage} />
        </Panel>
      </div>
    </div>
  );
}
