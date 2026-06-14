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
 * The current session only if it is present AND not expired. An expired access token whose stale JSON
 * still sits in storage must NOT grant page access — otherwise every protected page (incl. /admin)
 * renders and then 401-storms on the first API call. We proactively clear the dead session so the
 * redirect to /login is clean. expires_at is ISO-8601 from the auth response; a missing/invalid value
 * is treated as non-expiring (the API layer still enforces expiry authoritatively on each request).
 */
function activeSession(): Session | null {
  const session = loadSession();
  if (!session) return null;
  if (session.expires_at) {
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
