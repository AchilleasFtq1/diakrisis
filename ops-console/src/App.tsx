import { Navigate, Route, Routes } from 'react-router-dom';
import type { ReactNode } from 'react';
import { clearSession, loadSession } from './lib/auth';
import type { Session } from './lib/types';
import { Layout } from './components/Layout';
import Login from './pages/Login';
import Overview from './pages/Overview';
import DecisionDetail from './pages/DecisionDetail';
import Approvals from './pages/Approvals';
import AccountPosture from './pages/AccountPosture';
import Beneficiaries from './pages/Beneficiaries';
import Outcomes from './pages/Outcomes';
import AdminUsers from './pages/AdminUsers';
import Reference from './pages/Reference';

/**
 * The current session, if it can still be used. The access token is short-lived (~15 min) but the
 * session also carries a long-lived (~30 day) refresh token, and the API layer (api.ts) transparently
 * swaps an expired access token for a fresh one via that refresh token on the next call. So an expired
 * access token does NOT end the session: we only treat it as dead — clearing it and redirecting to
 * /login — when the access token has expired AND there is no refresh token left to renew it. (Clearing
 * on access-token expiry alone would bounce an analyst to the login screen every ~15 minutes and wipe
 * the still-valid refresh token, defeating the whole refresh mechanism.) expires_at is ISO-8601; a
 * missing/invalid value is treated as non-expiring (the API layer enforces expiry authoritatively).
 */
function activeSession(): Session | null {
  const session = loadSession();
  if (!session) return null;
  if (session.expires_at && !session.refresh_token) {
    const expiresAt = new Date(session.expires_at).getTime();
    if (Number.isFinite(expiresAt) && expiresAt <= Date.now()) {
      clearSession();
      return null;
    }
  }
  return session;
}

function Protected({ children }: { children: ReactNode }) {
  return activeSession() ? <Layout>{children}</Layout> : <Navigate to="/login" replace />;
}

/**
 * ADMIN-only routing guard (defense-in-depth / UX). Non-admins are redirected at the routing layer
 * instead of relying solely on the page body's in-panel "Restricted to ADMIN" message. The gateway and
 * IAM remain the authoritative authorization enforcement.
 */
function AdminRoute({ children }: { children: ReactNode }) {
  const session = activeSession();
  if (!session) return <Navigate to="/login" replace />;
  if (!session.roles?.includes('ADMIN')) return <Navigate to="/overview" replace />;
  return <Layout>{children}</Layout>;
}

export function App() {
  return (
    <Routes>
      <Route path="/login" element={<Login />} />
      <Route path="/overview" element={<Protected><Overview /></Protected>} />
      <Route path="/decisions/:id" element={<Protected><DecisionDetail /></Protected>} />
      <Route path="/approvals" element={<Protected><Approvals /></Protected>} />
      <Route path="/accounts" element={<Protected><AccountPosture /></Protected>} />
      <Route path="/accounts/:id" element={<Protected><AccountPosture /></Protected>} />
      <Route path="/beneficiaries" element={<Protected><Beneficiaries /></Protected>} />
      <Route path="/outcomes" element={<Protected><Outcomes /></Protected>} />
      <Route path="/reference" element={<Protected><Reference /></Protected>} />
      <Route path="/admin" element={<AdminRoute><AdminUsers /></AdminRoute>} />
      <Route path="*" element={<Navigate to="/overview" replace />} />
    </Routes>
  );
}
