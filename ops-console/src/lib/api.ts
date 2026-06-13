import { clearSession, loadSession, saveSession } from './auth';
import type {
  AccountView,
  ApprovalEntry,
  Counters,
  DecisionDetail,
  FeedEntry,
  Session,
} from './types';

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
  feed: () => request<FeedEntry[]>('/ops/feed'),
  counters: () => request<Counters>('/ops/counters'),
  approvals: () => request<ApprovalEntry[]>('/ops/approvals'),
  decision: (id: string) => request<DecisionDetail>(`/ops/decisions/${encodeURIComponent(id)}`),
  account: (id: string) => request<AccountView>(`/ops/accounts/${encodeURIComponent(id)}`),
  approve: (id: string) => request<void>(`/actions/${encodeURIComponent(id)}/approve`, { method: 'POST' }),
  reject: (id: string) => request<void>(`/actions/${encodeURIComponent(id)}/reject`, { method: 'POST' }),
};
