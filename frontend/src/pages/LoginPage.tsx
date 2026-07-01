import { FormEvent, useMemo, useState } from 'react';
import { Navigate, useLocation, useNavigate } from 'react-router-dom';
import {
  ArrowRight,
  Database,
  Eye,
  EyeOff,
  LogIn,
  ShieldCheck,
  UserCog,
  UserPlus,
} from 'lucide-react';
import { useAuth } from '../context/AuthContext';

type Mode = 'login' | 'register';

const MIN_PASSWORD_LENGTH = 6;

const accessHighlights = [
  {
    label: '数据隔离',
    value: '账号分区',
    icon: Database,
  },
  {
    label: '管理端',
    value: '用户与密码',
    icon: UserCog,
  },
  {
    label: '登录方式',
    value: '注册 / 登录',
    icon: ShieldCheck,
  },
];

export default function LoginPage() {
  const { user, login, register } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const [mode, setMode] = useState<Mode>('login');
  const [username, setUsername] = useState('');
  const [displayName, setDisplayName] = useState('');
  const [password, setPassword] = useState('');
  const [showPassword, setShowPassword] = useState(false);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');

  const from = useMemo(() => {
    const state = location.state as { from?: string } | null;
    return state?.from || '/history';
  }, [location.state]);

  if (user) {
    return <Navigate to={from} replace />;
  }

  const handleSubmit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (mode === 'register' && password.length < MIN_PASSWORD_LENGTH) {
      setError('密码长度至少为 6 位');
      return;
    }
    setLoading(true);
    setError('');
    try {
      if (mode === 'login') {
        await login({ username, password });
      } else {
        await register({ username, password, displayName });
      }
      navigate(from, { replace: true });
    } catch (err) {
      setError(err instanceof Error ? err.message : '登录失败');
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="min-h-screen bg-slate-100 text-slate-900 dark:bg-slate-950 dark:text-slate-100">
      <div className="min-h-screen bg-[linear-gradient(135deg,_rgba(241,245,249,0.96),_rgba(238,242,255,0.88)),radial-gradient(circle_at_top_left,_rgba(99,102,241,0.20),_transparent_34%)] dark:bg-[linear-gradient(135deg,_rgba(15,23,42,0.98),_rgba(30,41,59,0.94)),radial-gradient(circle_at_top_left,_rgba(99,102,241,0.16),_transparent_32%)]">
        <main className="mx-auto flex min-h-screen w-full max-w-6xl items-center px-5 py-12 sm:px-8 sm:py-14">
          <div className="grid w-full items-stretch gap-6 xl:grid-cols-[minmax(0,1fr)_420px]">
            <section className="hidden overflow-hidden rounded-2xl border border-white/70 bg-white/78 shadow-lg shadow-slate-200/70 backdrop-blur-xl dark:border-slate-700/60 dark:bg-slate-900/72 dark:shadow-slate-950/40 xl:flex">
              <div className="flex w-full flex-col justify-center gap-8 p-10">
                <div className="flex items-center gap-3">
                  <div className="flex h-11 w-11 items-center justify-center rounded-xl bg-primary-500 text-white shadow-lg shadow-primary-500/25">
                    <ShieldCheck className="h-5 w-5" />
                  </div>
                  <div>
                    <div className="text-xs font-semibold uppercase tracking-[0.16em] text-slate-400 dark:text-slate-500">
                      AI Interview
                    </div>
                    <div className="text-lg font-bold text-slate-900 dark:text-white">
                      智能面试平台
                    </div>
                  </div>
                </div>

                <div className="max-w-2xl">
                  <h1 className="max-w-xl text-4xl font-bold leading-tight text-slate-950 dark:text-white">
                    进入你的面试工作台
                  </h1>
                  <p className="mt-4 max-w-2xl text-sm leading-6 text-slate-600 dark:text-slate-300">
                    简历、知识库、面试记录与系统管理统一收束在内部工作台中，登录后按账号权限进入对应空间。
                  </p>
                </div>

                <div className="grid max-w-2xl grid-cols-3 gap-2 text-sm">
                  {accessHighlights.map((item) => (
                    <div
                      key={item.label}
                      className="rounded-xl border border-slate-200/80 bg-slate-50/80 p-3 dark:border-slate-700/70 dark:bg-slate-800/58"
                    >
                      <div className="mb-3 flex h-8 w-8 items-center justify-center rounded-lg bg-white text-primary-600 shadow-sm dark:bg-slate-900 dark:text-primary-400">
                        <item.icon className="h-4 w-4" />
                      </div>
                      <div className="text-xs text-slate-500 dark:text-slate-400">{item.label}</div>
                      <div className="mt-1 font-semibold text-slate-900 dark:text-white">{item.value}</div>
                    </div>
                  ))}
                </div>
              </div>
            </section>

            <section className="flex items-center justify-center rounded-2xl border border-white/80 bg-white/88 p-7 shadow-lg shadow-slate-200/70 backdrop-blur-xl dark:border-slate-700/60 dark:bg-slate-900/82 dark:shadow-slate-950/40 sm:p-8">
              <div className="w-full max-w-sm">
                <div className="mb-7 flex items-center justify-between gap-3">
                  <div>
                    <p className="text-xs font-semibold uppercase tracking-[0.16em] text-primary-600 dark:text-primary-400">
                      Workspace Access
                    </p>
                    <h2 className="mt-2 text-2xl font-bold text-slate-950 dark:text-white">
                      {mode === 'login' ? '欢迎回来' : '创建账号'}
                    </h2>
                    <p className="mt-1 text-sm text-slate-500 dark:text-slate-400">
                      {mode === 'login' ? '输入账号密码进入系统' : '注册后即可进入个人工作台'}
                    </p>
                  </div>
                  <div className="flex h-10 w-10 items-center justify-center rounded-xl bg-primary-500/10 text-primary-600 dark:bg-primary-400/10 dark:text-primary-300">
                    {mode === 'login' ? <LogIn className="h-5 w-5" /> : <UserPlus className="h-5 w-5" />}
                  </div>
                </div>

                <div className="mb-6 flex rounded-xl bg-slate-100 p-1 dark:bg-slate-800">
                  <button
                    type="button"
                    onClick={() => setMode('login')}
                    className={`h-9 flex-1 rounded-lg text-sm font-medium transition-colors ${
                      mode === 'login'
                        ? 'bg-white text-slate-950 shadow-sm dark:bg-slate-900 dark:text-white'
                        : 'text-slate-500 hover:text-slate-700 dark:text-slate-400 dark:hover:text-slate-200'
                    }`}
                  >
                    登录
                  </button>
                  <button
                    type="button"
                    onClick={() => setMode('register')}
                    className={`h-9 flex-1 rounded-lg text-sm font-medium transition-colors ${
                      mode === 'register'
                        ? 'bg-white text-slate-950 shadow-sm dark:bg-slate-900 dark:text-white'
                        : 'text-slate-500 hover:text-slate-700 dark:text-slate-400 dark:hover:text-slate-200'
                    }`}
                  >
                    注册
                  </button>
                </div>

                <form onSubmit={handleSubmit} className="space-y-5">
                  <label className="block">
                    <span className="mb-1.5 block text-sm text-slate-600 dark:text-slate-300">用户名</span>
                    <input
                      value={username}
                      onChange={(e) => setUsername(e.target.value)}
                      autoComplete="username"
                      className="dark-input h-12 w-full rounded-xl px-4"
                      placeholder="请输入用户名"
                    />
                  </label>

                  {mode === 'register' && (
                    <label className="block">
                      <span className="mb-1.5 block text-sm text-slate-600 dark:text-slate-300">
                        显示名称
                      </span>
                      <input
                        value={displayName}
                        onChange={(e) => setDisplayName(e.target.value)}
                        autoComplete="nickname"
                        className="dark-input h-12 w-full rounded-xl px-4"
                        placeholder="请输入显示名称"
                      />
                    </label>
                  )}

                  <label className="block">
                    <span className="mb-1.5 block text-sm text-slate-600 dark:text-slate-300">密码</span>
                    <div className="relative">
                      <input
                        value={password}
                        onChange={(e) => setPassword(e.target.value)}
                        type={showPassword ? 'text' : 'password'}
                        autoComplete={mode === 'login' ? 'current-password' : 'new-password'}
                        minLength={mode === 'register' ? MIN_PASSWORD_LENGTH : undefined}
                        className="dark-input h-12 w-full rounded-xl px-4 pr-11"
                        placeholder="请输入密码"
                      />
                      <button
                        type="button"
                        onClick={() => setShowPassword((prev) => !prev)}
                        className="absolute inset-y-0 right-0 flex w-10 items-center justify-center text-slate-400 transition-colors hover:text-slate-600 dark:hover:text-slate-200"
                        title={showPassword ? '隐藏密码' : '显示密码'}
                      >
                        {showPassword ? <EyeOff className="h-4 w-4" /> : <Eye className="h-4 w-4" />}
                      </button>
                    </div>
                  </label>

                  {error && (
                    <div className="rounded-xl border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-600 dark:border-red-900/40 dark:bg-red-950/30 dark:text-red-300">
                      {error}
                    </div>
                  )}

                  <button
                    type="submit"
                    disabled={loading}
                    className="btn-primary flex h-12 w-full items-center justify-center gap-2 rounded-xl disabled:cursor-not-allowed disabled:opacity-70"
                  >
                    <span>{loading ? '处理中...' : mode === 'login' ? '登录' : '注册并进入'}</span>
                    <ArrowRight className="h-4 w-4" />
                  </button>
                </form>
              </div>
            </section>
          </div>
        </main>
      </div>
    </div>
  );
}
