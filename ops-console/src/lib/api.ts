import { clearSession, loadSession, saveSession } from './auth';
import type {
  AccountView,
  ApprovalEntry,
  Counters,
  CounterpartyView,
  DecisionDetail,
  FeedEntry,
  Outcome,
  OutcomeView,
  Page,
  Session,
  UserView,
} from './types';

/** Lifecycle actions an analyst can drive from the console (subset that fits OPS/APPROVER). */
export type LifecycleAction = 'release' | 'cancel' | 'approve' | 'reject';

export interface FeedParams {
  page?: number;
  size?: number;
  outcomes?: Outcome[];
  q?: string;
}

export interface ApprovalParams {
  page?: number;
  size?: number;
  q?: string;
  initiator?: string;
}

function queryString(params: Record<string, string | number | undefined>): string {
  const qs = new URLSearchParams();
  for (const [k, v] of Object.entries(params)) {
    if (v !== undefined && v !== '') qs.set(k, String(v));
  }
  const s = qs.toString();
  return s ? `?${s}` : '';
}

const GATEWAY: string = import.meta.env.VITE_GATEWAY_URL ?? 'http://localhost:8080';

export class ApiError extends Error {
  constructor(public status: number, message: string) {
    super(message);
  }
}

/**
 * Single-flight refresh: a 401 swaps the expired access token for a fresh one via the rotating
 * refresh token, and the original request is retried once. Concurrent 401s share one refresh call.
 * Only when refresh itself fails do we clear the session and surface 401 (→ redirect to login).
 */
let refreshInFlight: Promise<Session | null> | null = null;

async function tryRefresh(): Promise<Session | null> {
  const current = loadSession();
  if (!current?.refresh_token) return null;
  if (!refreshInFlight) {
    refreshInFlight = (async (): Promise<Session | null> => {
      try {
        const res = await fetch(`${GATEWAY}/auth/refresh`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ refresh_token: current.refresh_token }),
        });
        if (!res.ok) {
          clearSession();
          return null;
        }
        const data = await res.json();
        const next: Session = {
          token: data.token,
          refresh_token: data.refresh_token ?? current.refresh_token,
          expires_at: data.expires_at ?? null,
          sub: current.sub,
          roles: current.roles,
        };
        saveSession(next);
        return next;
      } catch {
        clearSession();
        return null;
      } finally {
        refreshInFlight = null;
      }
    })();
  }
  return refreshInFlight;
}

async function request<T>(path: string, init: RequestInit = {}, allowRetry = true): Promise<T> {
  const session = loadSession();
  const headers = new Headers(init.headers);
  if (session) headers.set('Authorization', `Bearer ${session.token}`);
  if (init.body) headers.set('Content-Type', 'application/json');

  const res = await fetch(`${GATEWAY}${path}`, { ...init, headers });
  if (res.status === 401) {
    if (allowRetry) {
      const refreshed = await tryRefresh();
      if (refreshed) return request<T>(path, init, false);
    }
    clearSession();
    throw new ApiError(401, 'Session expired — sign in again.');
  }
  if (!res.ok) {
    throw new ApiError(res.status, `${res.status} on ${path}`);
  }
  if (res.status === 204) return undefined as T;
  const text = await res.text();
  return (text ? JSON.parse(text) : undefined) as T;
}

export async function login(username: string, password: string): Promise<Session> {
  const res = await fetch(`${GATEWAY}/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password }),
  });
  if (!res.ok) throw new ApiError(res.status, 'Invalid credentials');
  const data = await res.json();
  const session: Session = {
    token: data.token,
    refresh_token: data.refresh_token ?? null,
    expires_at: data.expires_at ?? null,
    sub: data.sub ?? username,
    roles: data.roles ?? [],
  };
  saveSession(session);
  return session;
}

export const api = {
  feed: (params: FeedParams = {}) =>
    request<Page<FeedEntry>>(
      `/ops/feed${queryString({
        page: params.page,
        size: params.size,
        outcomes: params.outcomes?.length ? params.outcomes.join(',') : undefined,
        q: params.q,
      })}`,
    ),
  counters: () => request<Counters>('/ops/counters'),
  approvals: (params: ApprovalParams = {}) =>
    request<Page<ApprovalEntry>>(
      `/ops/approvals${queryString({
        page: params.page,
        size: params.size,
        q: params.q,
        initiator: params.initiator,
      })}`,
    ),
  decision: (id: string) => request<DecisionDetail>(`/ops/decisions/${encodeURIComponent(id)}`),
  account: (id: string) => request<AccountView>(`/ops/accounts/${encodeURIComponent(id)}`),
  accountHistory: (id: string, params: { page?: number; size?: number } = {}) =>
    request<Page<FeedEntry>>(
      `/ops/accounts/${encodeURIComponent(id)}/history${queryString({ page: params.page, size: params.size })}`,
    ),
  approve: (id: string) => request<void>(`/actions/${encodeURIComponent(id)}/approve`, { method: 'POST' }),
  reject: (id: string) => request<void>(`/actions/${encodeURIComponent(id)}/reject`, { method: 'POST' }),
  act: (id: string, action: LifecycleAction) =>
    request<unknown>(`/actions/${encodeURIComponent(id)}/${action}`, { method: 'POST' }),
  counterparties: (params: { page?: number; size?: number; q?: string } = {}) =>
    request<Page<CounterpartyView>>(
      `/ops/counterparties${queryString({ page: params.page, size: params.size, q: params.q })}`,
    ),
  outcomes: (params: { page?: number; size?: number; type?: string } = {}) =>
    request<Page<OutcomeView>>(
      `/ops/outcomes${queryString({ page: params.page, size: params.size, type: params.type })}`,
    ),
  // ADMIN-only user management (IAM /admin/users, gated to ADMIN at the gateway edge + in IAM).
  admin: {
    users: () => request<UserView[]>('/admin/users'),
    createUser: (body: { username: string; password: string; role?: string; account_id?: string }) =>
      request<UserView>('/admin/users', { method: 'POST', body: JSON.stringify(body) }),
    assignRole: (username: string, role: string) =>
      request<UserView>(`/admin/users/${encodeURIComponent(username)}/roles`, {
        method: 'POST',
        body: JSON.stringify({ role }),
      }),
    setEnabled: (username: string, enabled: boolean) =>
      request<UserView>(`/admin/users/${encodeURIComponent(username)}/${enabled ? 'enable' : 'disable'}`, {
        method: 'POST',
      }),
    deleteUser: (username: string) =>
      request<void>(`/admin/users/${encodeURIComponent(username)}`, { method: 'DELETE' }),
  },
};
