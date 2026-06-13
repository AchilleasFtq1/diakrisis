import { useState, type FormEvent } from 'react';
import { useNavigate } from 'react-router-dom';
import { login } from '../lib/api';

export default function Login() {
  const navigate = useNavigate();
  const [username, setUsername] = useState('ops-user');
  const [password, setPassword] = useState('demo');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function onSubmit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await login(username.trim(), password);
      navigate('/overview');
    } catch {
      setError('Invalid credentials — try ops-user / demo or admin / admin.');
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="min-h-screen grid place-items-center grid-bg relative">
      <form
        onSubmit={onSubmit}
        className="relative w-[380px] bg-panel border border-line rounded-2xl p-8 shadow-2xl"
      >
        <div className="flex items-center gap-2.5 mb-1.5">
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
          <span className="font-mono text-[15px] font-semibold tracking-[0.16em] text-fg">DIAKRISIS</span>
        </div>
        <div className="text-[12.5px] text-muted mb-6">Fraud-Ops Console · restricted access</div>

        <label className="block font-mono text-[10.5px] tracking-[0.1em] uppercase text-fg-2 mb-1.5">
          Username
        </label>
        <input
          value={username}
          onChange={(e) => setUsername(e.target.value)}
          className="w-full h-[42px] rounded-lg bg-ink border border-line-2 px-3 mb-4 font-mono text-[13px] text-fg outline-none focus:border-cyan"
        />

        <label className="block font-mono text-[10.5px] tracking-[0.1em] uppercase text-fg-2 mb-1.5">
          Password
        </label>
        <input
          type="password"
          value={password}
          onChange={(e) => setPassword(e.target.value)}
          className="w-full h-[42px] rounded-lg bg-ink border border-line-2 px-3 mb-5 font-mono text-[13px] text-fg outline-none focus:border-cyan tracking-[0.16em]"
        />

        {error && <div className="text-[12px] text-block mb-3">{error}</div>}

        <button
          type="submit"
          disabled={busy}
          className="w-full h-[42px] rounded-lg bg-cyan/90 hover:bg-cyan text-ink font-semibold text-[13px] transition-colors disabled:opacity-60"
        >
          {busy ? 'Authenticating…' : 'Sign in'}
        </button>
        <div className="text-[11px] text-muted mt-4 font-mono">
          demo: ops-user / demo · admin / admin
        </div>
      </form>
    </div>
  );
}
