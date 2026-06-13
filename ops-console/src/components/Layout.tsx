import { NavLink, useNavigate } from 'react-router-dom';
import { Activity, Award, BookOpen, Network, ShieldCheck, Users, UserCog, LogOut, Radar } from 'lucide-react';
import { clearSession, loadSession } from '../lib/auth';
import type { ReactNode } from 'react';

const NAV = [
  { to: '/overview', label: 'Overview', icon: Activity },
  { to: '/approvals', label: 'Approvals', icon: ShieldCheck },
  { to: '/accounts', label: 'Accounts', icon: Users },
  { to: '/beneficiaries', label: 'Beneficiaries', icon: Network },
  { to: '/outcomes', label: 'Outcomes', icon: Award },
  { to: '/reference', label: 'Reference', icon: BookOpen },
  { to: '/admin', label: 'Admin', icon: UserCog, adminOnly: true },
];

export function Layout({ children }: { children: ReactNode }) {
  const navigate = useNavigate();
  const session = loadSession();
  const role = session?.roles?.[0] ?? 'OPS';
  const isAdmin = session?.roles?.includes('ADMIN') ?? false;
  const nav = NAV.filter((item) => !item.adminOnly || isAdmin);

  return (
    <div className="flex min-h-screen">
      <aside className="w-60 shrink-0 bg-panel border-r border-line flex flex-col px-3 py-5 sticky top-0 h-screen">
        <div className="flex items-center gap-2.5 px-2 pb-5 border-b border-line">
          <div className="w-[30px] h-[30px] rounded-[7px] bg-ink grid place-items-center border border-line-2">
            <div
              className="w-[13px] h-[13px] rounded-full"
              style={{
                border: '2px solid #4CC2D6',
                borderRightColor: '#F85149',
                borderBottomColor: '#F85149',
                transform: 'rotate(-30deg)',
              }}
            />
          </div>
          <div className="leading-tight">
            <div className="font-mono text-[13px] font-semibold tracking-[0.16em] text-fg">DIAKRISIS</div>
            <div className="text-[10px] text-muted">Fraud-Ops Console</div>
          </div>
        </div>

        <nav className="flex flex-col gap-0.5 mt-4 flex-1">
          {nav.map(({ to, label, icon: Icon }) => (
            <NavLink
              key={to}
              to={to}
              className={({ isActive }) =>
                `flex items-center gap-2.5 px-3 py-2 rounded-lg text-[13px] font-medium transition-colors ${
                  isActive ? 'bg-white/10 text-fg' : 'text-fg-2 hover:bg-white/5 hover:text-fg'
                }`
              }
            >
              <Icon size={15} className="opacity-80" /> {label}
            </NavLink>
          ))}
        </nav>

        <div className="border-t border-line pt-3 mt-2">
          <div className="flex items-center gap-2 px-2 py-1.5 mb-2">
            <Radar size={13} className="text-cyan" />
            <span className="font-mono text-[10px] text-cyan-2 tracking-wide">live engine</span>
          </div>
          <div className="flex items-center justify-between px-2">
            <div className="text-[11px] text-fg-2 leading-tight">
              <div className="font-mono text-fg">{session?.sub ?? 'analyst'}</div>
              <span className="font-mono text-[9.5px] text-approval bg-approval/12 border border-approval/30 rounded px-1.5 py-0.5">
                {role}
              </span>
            </div>
            <button
              onClick={() => {
                clearSession();
                navigate('/login');
              }}
              className="text-fg-2 hover:text-block transition-colors"
              title="Sign out"
            >
              <LogOut size={16} />
            </button>
          </div>
        </div>
      </aside>

      <main className="flex-1 min-w-0">{children}</main>
    </div>
  );
}

export function PageHead({ eyebrow, title, right }: { eyebrow: string; title: string; right?: ReactNode }) {
  return (
    <header className="flex items-end justify-between gap-4 px-8 pt-7 pb-5 border-b border-line">
      <div>
        <p className="font-mono text-[11px] tracking-[0.12em] uppercase text-cyan mb-1.5">{eyebrow}</p>
        <h1 className="text-[22px] font-semibold text-fg leading-none">{title}</h1>
      </div>
      {right}
    </header>
  );
}
