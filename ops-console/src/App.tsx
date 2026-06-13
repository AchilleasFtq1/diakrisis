import { Navigate, Route, Routes } from 'react-router-dom';
import type { ReactNode } from 'react';
import { loadSession } from './lib/auth';
import { Layout } from './components/Layout';
import Login from './pages/Login';
import Overview from './pages/Overview';
import DecisionDetail from './pages/DecisionDetail';
import Approvals from './pages/Approvals';
import AccountPosture from './pages/AccountPosture';

function Protected({ children }: { children: ReactNode }) {
  return loadSession() ? <Layout>{children}</Layout> : <Navigate to="/login" replace />;
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
      <Route path="*" element={<Navigate to="/overview" replace />} />
    </Routes>
  );
}
