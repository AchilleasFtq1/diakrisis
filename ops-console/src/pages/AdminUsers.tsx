import { useEffect, useMemo, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Pencil, Plus, Save, Shield, Trash2, UserPlus, X } from 'lucide-react';
import { api, ApiError } from '../lib/api';
import { loadSession } from '../lib/auth';
import { PageHead } from '../components/Layout';
import { Panel, Pagination, SearchInput } from '../components/widgets';
import { Mono } from '../components/primitives';
import type { Role, UserView } from '../lib/types';

const ROLES: Role[] = ['CUSTOMER', 'APPROVER', 'OPS', 'ADMIN'];
const PAGE_SIZE = 6;

const ROLE_HEX: Record<string, string> = {
  ADMIN: '#a371f7',
  OPS: '#4cc2d6',
  APPROVER: '#d29922',
  CUSTOMER: '#5c6773',
};

const inputCls = 'w-full h-[38px] rounded-lg bg-ink border border-line-2 px-3 font-mono text-[12px] text-fg outline-none focus:border-cyan';

export default function AdminUsers() {
  const session = loadSession();
  const isAdmin = session?.roles?.includes('ADMIN') ?? false;
  const me = session?.sub;

  const qc = useQueryClient();
  const [query, setQuery] = useState('');
  const [page, setPage] = useState(1);
  const [showCreate, setShowCreate] = useState(false);
  const [createForm, setCreateForm] = useState({ username: '', password: '', role: 'OPS' as Role, account_id: '' });
  const [editing, setEditing] = useState<UserView | null>(null);
  const [editForm, setEditForm] = useState({ role: 'OPS', account_id: '', enabled: true });
  const [banner, setBanner] = useState<{ kind: 'ok' | 'err'; text: string } | null>(null);

  const users = useQuery({ queryKey: ['admin-users'], queryFn: api.admin.users, enabled: isAdmin });

  useEffect(() => {
    if (editing) {
      setEditForm({ role: editing.roles?.[0] ?? 'CUSTOMER', account_id: editing.account_id ?? '', enabled: editing.enabled });
    }
  }, [editing]);

  const refresh = () => qc.invalidateQueries({ queryKey: ['admin-users'] });
  const fail = (e: unknown) =>
    setBanner({ kind: 'err', text: e instanceof ApiError ? `Failed (${e.status}).` : 'Action failed.' });

  const createMut = useMutation({
    mutationFn: () =>
      api.admin.createUser({
        username: createForm.username.trim(),
        password: createForm.password,
        role: createForm.role,
        account_id: createForm.role === 'CUSTOMER' && createForm.account_id.trim() ? createForm.account_id.trim() : undefined,
      }),
    onSuccess: (u) => {
      setBanner({ kind: 'ok', text: `Created ${u.username}.` });
      setCreateForm({ username: '', password: '', role: 'OPS', account_id: '' });
      setShowCreate(false);
      refresh();
    },
    onError: fail,
  });
  const updateMut = useMutation({
    mutationFn: () =>
      api.admin.updateUser(editing!.username, {
        role: editForm.role,
        enabled: editForm.enabled,
        account_id: editForm.account_id.trim() ? editForm.account_id.trim() : undefined,
      }),
    onSuccess: (u) => {
      setBanner({ kind: 'ok', text: `Updated ${u.username} → ${u.roles.join(', ')}${u.enabled ? '' : ' (disabled)'}` });
      setEditing(null);
      refresh();
    },
    onError: fail,
  });
  const deleteMut = useMutation({
    mutationFn: (username: string) => api.admin.deleteUser(username),
    onSuccess: () => { setBanner({ kind: 'ok', text: 'User deleted.' }); refresh(); },
    onError: fail,
  });

  const rows = users.data ?? [];
  const filtered = useMemo(() => {
    const q = query.toLowerCase();
    return rows
      .filter((u) => !q || [u.username, u.account_id, ...(u.roles ?? [])].filter(Boolean).join(' ').toLowerCase().includes(q))
      .sort((a, b) => a.username.localeCompare(b.username));
  }, [rows, query]);
  const pageCount = Math.max(1, Math.ceil(filtered.length / PAGE_SIZE));
  const safePage = Math.min(page, pageCount);
  const pageRows = filtered.slice((safePage - 1) * PAGE_SIZE, safePage * PAGE_SIZE);

  if (!isAdmin) {
    return (
      <div>
        <PageHead eyebrow="Administration" title="User management" />
        <div className="px-8 py-16 text-center text-muted text-[13px]">
          <Shield size={20} className="mx-auto mb-3 text-muted" />
          Restricted to ADMIN. You are signed in as <Mono className="text-fg-2">{me}</Mono>.
        </div>
      </div>
    );
  }

  const busy = createMut.isPending || updateMut.isPending || deleteMut.isPending;
  const editingSelf = editing?.username === me;

  return (
    <div>
      <PageHead
        eyebrow="Administration"
        title="User management"
        right={
          <div className="flex items-center gap-3">
            <SearchInput value={query} onChange={(v) => { setQuery(v); setPage(1); }} placeholder="user · role · account…" />
            <button
              onClick={() => { setShowCreate((v) => !v); setEditing(null); }}
              className="flex items-center gap-1.5 text-[12px] font-semibold text-ink bg-cyan hover:bg-cyan/90 rounded px-3 py-1.5"
            >
              {showCreate ? <X size={14} /> : <Plus size={14} />} {showCreate ? 'Close' : 'New user'}
            </button>
          </div>
        }
      />

      <div className="px-8 py-6 space-y-4">
        {banner && (
          <div
            className={`flex items-center justify-between rounded-lg border px-3.5 py-2 text-[12px] ${
              banner.kind === 'ok' ? 'text-allow bg-allow/10 border-allow/30' : 'text-block bg-block/10 border-block/30'
            }`}
          >
            <span className="font-mono">{banner.text}</span>
            <button onClick={() => setBanner(null)} className="text-muted hover:text-fg"><X size={13} /></button>
          </div>
        )}

        {showCreate && (
          <Panel title="Create user">
            <div className="grid grid-cols-1 md:grid-cols-5 gap-3 items-end">
              <Field label="Username">
                <input value={createForm.username} onChange={(e) => setCreateForm({ ...createForm, username: e.target.value })} className={inputCls} placeholder="j.okafor" />
              </Field>
              <Field label="Password">
                <input type="password" value={createForm.password} onChange={(e) => setCreateForm({ ...createForm, password: e.target.value })} className={inputCls} placeholder="min 4 chars" />
              </Field>
              <Field label="Role">
                <select value={createForm.role} onChange={(e) => setCreateForm({ ...createForm, role: e.target.value as Role })} className={inputCls}>
                  {ROLES.map((r) => <option key={r} value={r}>{r}</option>)}
                </select>
              </Field>
              <Field label="Account (CUSTOMER)">
                <input value={createForm.account_id} onChange={(e) => setCreateForm({ ...createForm, account_id: e.target.value })} disabled={createForm.role !== 'CUSTOMER'} className={`${inputCls} disabled:opacity-40`} placeholder="acc-A" />
              </Field>
              <button
                onClick={() => createMut.mutate()}
                disabled={busy || createForm.username.trim().length < 3 || createForm.password.length < 4}
                className="flex items-center justify-center gap-1.5 h-[38px] text-[12px] font-semibold text-ink bg-allow hover:bg-allow/90 rounded px-3 disabled:opacity-45 disabled:cursor-not-allowed"
              >
                <UserPlus size={14} /> Create
              </button>
            </div>
          </Panel>
        )}

        {editing && (
          <Panel title={`Edit ${editing.username}`} right={<button onClick={() => setEditing(null)} className="text-muted hover:text-fg"><X size={14} /></button>}>
            <div className="grid grid-cols-1 md:grid-cols-4 gap-3 items-end">
              <Field label="Role">
                <select value={editForm.role} disabled={editingSelf} onChange={(e) => setEditForm({ ...editForm, role: e.target.value })} className={`${inputCls} disabled:opacity-40`}>
                  {ROLES.map((r) => <option key={r} value={r}>{r}</option>)}
                </select>
              </Field>
              <Field label="Account">
                <input value={editForm.account_id} onChange={(e) => setEditForm({ ...editForm, account_id: e.target.value })} className={inputCls} placeholder="acc-A (CUSTOMER)" />
              </Field>
              <Field label="Status">
                <select value={editForm.enabled ? 'enabled' : 'disabled'} disabled={editingSelf} onChange={(e) => setEditForm({ ...editForm, enabled: e.target.value === 'enabled' })} className={`${inputCls} disabled:opacity-40`}>
                  <option value="enabled">Enabled</option>
                  <option value="disabled">Disabled</option>
                </select>
              </Field>
              <button
                onClick={() => updateMut.mutate()}
                disabled={busy}
                className="flex items-center justify-center gap-1.5 h-[38px] text-[12px] font-semibold text-ink bg-allow hover:bg-allow/90 rounded px-3 disabled:opacity-45 disabled:cursor-not-allowed"
              >
                <Save size={14} /> Save changes
              </button>
            </div>
            {editingSelf && <p className="mt-2.5 font-mono text-[10.5px] text-muted">Role and status are locked when editing your own account (lockout guard).</p>}
          </Panel>
        )}

        <Panel title="Users" right={<span className="font-mono text-[10.5px] text-muted">{filtered.length} users</span>}>
          {filtered.length > PAGE_SIZE && (
            <div className="border-b border-line/70 mb-2 -mt-1">
              <Pagination page={safePage} pageSize={PAGE_SIZE} total={filtered.length} onPage={setPage} />
            </div>
          )}
          <div className="overflow-x-auto">
            <table className="w-full text-[12.5px]">
              <thead>
                <tr className="text-left font-mono text-[10px] tracking-[0.08em] uppercase text-muted border-b border-line">
                  <th className="py-2 pr-3 font-medium">User</th>
                  <th className="py-2 pr-3 font-medium">Role</th>
                  <th className="py-2 pr-3 font-medium">Account</th>
                  <th className="py-2 pr-3 font-medium">Status</th>
                  <th className="py-2 pr-3 font-medium text-right">Manage</th>
                </tr>
              </thead>
              <tbody>
                {pageRows.map((u) => {
                  const self = u.username === me;
                  const role = u.roles?.[0] ?? 'CUSTOMER';
                  const hex = ROLE_HEX[role] ?? '#5c6773';
                  return (
                    <tr key={u.user_id} className={`border-b border-line/60 ${editing?.username === u.username ? 'bg-cyan/[0.04]' : ''}`}>
                      <td className="py-2.5 pr-3">
                        <Mono className="text-fg">{u.username}</Mono>
                        {self && <span className="ml-1.5 font-mono text-[9px] text-cyan">you</span>}
                      </td>
                      <td className="py-2.5 pr-3">
                        <span className="font-mono text-[10.5px] px-1.5 py-0.5 rounded border" style={{ color: hex, background: `${hex}1a`, borderColor: `${hex}55` }}>
                          {role}
                        </span>
                      </td>
                      <td className="py-2.5 pr-3"><Mono className="text-fg-2">{u.account_id ?? '—'}</Mono></td>
                      <td className="py-2.5 pr-3">
                        {u.enabled
                          ? <span className="font-mono text-[10.5px] text-allow">● enabled</span>
                          : <span className="font-mono text-[10.5px] text-muted">○ disabled</span>}
                      </td>
                      <td className="py-2.5 pr-3">
                        <div className="flex items-center gap-1.5 justify-end">
                          <button
                            onClick={() => { setEditing(u); setShowCreate(false); }}
                            className="flex items-center gap-1 font-mono text-[10.5px] px-2 py-1 rounded border border-line text-fg-2 hover:text-fg hover:border-cyan"
                          >
                            <Pencil size={12} /> Edit
                          </button>
                          <button
                            onClick={() => { if (confirm(`Delete user ${u.username}?`)) deleteMut.mutate(u.username); }}
                            disabled={self || busy}
                            title={self ? "can't delete yourself" : 'delete'}
                            className="flex items-center gap-1 font-mono text-[10.5px] px-2 py-1 rounded border border-block/40 text-block bg-block/5 hover:bg-block/15 disabled:opacity-35 disabled:cursor-not-allowed"
                          >
                            <Trash2 size={12} /> Delete
                          </button>
                        </div>
                      </td>
                    </tr>
                  );
                })}
                {filtered.length === 0 && (
                  <tr><td colSpan={5} className="py-8 text-center text-muted">{users.isLoading ? 'Loading…' : 'No users match.'}</td></tr>
                )}
              </tbody>
            </table>
          </div>
          <Pagination page={safePage} pageSize={PAGE_SIZE} total={filtered.length} onPage={setPage} />
        </Panel>
      </div>
    </div>
  );
}

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <label className="block">
      <span className="block font-mono text-[9px] uppercase tracking-wide text-muted mb-1">{label}</span>
      {children}
    </label>
  );
}
