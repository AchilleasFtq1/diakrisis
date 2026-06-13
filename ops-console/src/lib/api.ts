import { clearSession, loadSession, saveSession } from './auth';
import type {
  AccountView,
  ApprovalEntry,
  Counters,
  DecisionDetail,
  FeedEntry,
  Outcome,
  Page,
  Session,
} from './types';

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

async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const session = loadSession();
  const headers = new Headers(init.headers);
  if (session) headers.set('Authorization', `Bearer ${session.token}`);
  if (init.body) headers.set('Content-Type', 'application/json');

  const res = await fetch(`${GATEWAY}${path}`, { ...init, headers });
  if (res.status === 401) {
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
};
