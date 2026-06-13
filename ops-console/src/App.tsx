import { Navigate, Route, Routes } from 'react-router-dom';
import type { ReactNode } from 'react';
import { loadSession } from './lib/auth';
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
      <Route path="/beneficiaries" element={<Protected><Beneficiaries /></Protected>} />
      <Route path="/outcomes" element={<Protected><Outcomes /></Protected>} />
      <Route path="/reference" element={<Protected><Reference /></Protected>} />
      <Route path="/admin" element={<Protected><AdminUsers /></Protected>} />
      <Route path="*" element={<Navigate to="/overview" replace />} />
    </Routes>
  );
}
