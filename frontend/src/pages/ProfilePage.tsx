import {useEffect, useMemo, useState} from 'react';
import {useNavigate} from 'react-router-dom';
import {motion} from 'framer-motion';
import {
  Area,
  AreaChart,
  Bar,
  BarChart,
  CartesianGrid,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts';
import {
  AlertCircle,
  CalendarDays,
  CheckCircle,
  KeyRound,
  Loader2,
  Lock,
  Save,
  ShieldCheck,
  TrendingDown,
  TrendingUp,
  UserRound,
} from 'lucide-react';
import {profileApi} from '../api/auth';
import {useAuth} from '../context/AuthContext';
import {formatDateOnly} from '../utils/date';
import type {PeriodMetric, ProfileStats, WeaknessTrend} from '../types/auth';

const EMPTY_PERIOD: PeriodMetric = {
  days: 0,
  interviewCount: 0,
  previousInterviewCount: 0,
  interviewCountChange: 0,
  averageScore: null,
  previousAverageScore: null,
  averageScoreChange: null,
};

function formatScore(value: number | null | undefined): string {
  return value == null ? '-' : value.toFixed(value % 1 === 0 ? 0 : 1);
}

function formatDelta(value: number | null | undefined, suffix = ''): string {
  if (value == null) return '暂无对比';
  if (value === 0) return `持平${suffix}`;
  return `${value > 0 ? '+' : ''}${value.toFixed(value % 1 === 0 ? 0 : 1)}${suffix}`;
}

function DeltaBadge({value, suffix}: {value: number | null | undefined; suffix?: string}) {
  if (value == null) {
    return (
      <span className="inline-flex items-center px-2.5 py-1 rounded-lg bg-slate-100 dark:bg-slate-700 text-xs font-medium text-slate-500 dark:text-slate-400">
        暂无对比
      </span>
    );
  }
  const positive = value > 0;
  const neutral = value === 0;
  return (
    <span
      className={`inline-flex items-center gap-1 px-2.5 py-1 rounded-lg text-xs font-semibold ${
        neutral
          ? 'bg-slate-100 dark:bg-slate-700 text-slate-500 dark:text-slate-300'
          : positive
            ? 'bg-emerald-50 dark:bg-emerald-900/30 text-emerald-600 dark:text-emerald-400'
            : 'bg-rose-50 dark:bg-rose-900/30 text-rose-600 dark:text-rose-400'
      }`}
    >
      {positive ? <TrendingUp className="w-3.5 h-3.5" /> : !neutral && <TrendingDown className="w-3.5 h-3.5" />}
      {formatDelta(value, suffix)}
    </span>
  );
}

function PeriodCard({metric, label}: {metric: PeriodMetric; label: string}) {
  return (
    <motion.div
      className="bg-white dark:bg-slate-800 rounded-xl p-6 border border-slate-100 dark:border-slate-700 shadow-sm"
      initial={{opacity: 0, y: 18}}
      animate={{opacity: 1, y: 0}}
    >
      <div className="flex items-start justify-between gap-4">
        <div>
          <p className="text-sm text-slate-500 dark:text-slate-400">{label}</p>
          <div className="mt-3 flex items-end gap-2">
            <span className="text-3xl font-bold text-slate-900 dark:text-white">
              {metric.interviewCount}
            </span>
            <span className="pb-1 text-sm text-slate-400 dark:text-slate-500">场面试</span>
          </div>
        </div>
        <div className="w-11 h-11 rounded-xl bg-primary-50 dark:bg-primary-900/30 flex items-center justify-center text-primary-600 dark:text-primary-400">
          <CalendarDays className="w-5 h-5" />
        </div>
      </div>

      <div className="mt-5 grid grid-cols-2 gap-4">
        <div>
          <p className="text-xs text-slate-400 dark:text-slate-500 mb-1">平均分</p>
          <p className="text-xl font-semibold text-slate-800 dark:text-white">
            {formatScore(metric.averageScore)}
            <span className="text-sm font-normal text-slate-400 ml-1">分</span>
          </p>
        </div>
        <div>
          <p className="text-xs text-slate-400 dark:text-slate-500 mb-1">上一周期</p>
          <p className="text-xl font-semibold text-slate-800 dark:text-white">
            {metric.previousInterviewCount}
            <span className="text-sm font-normal text-slate-400 ml-1">场</span>
          </p>
        </div>
      </div>

      <div className="mt-5 flex flex-wrap items-center gap-2">
        <DeltaBadge value={metric.interviewCountChange} suffix=" 场" />
        <DeltaBadge value={metric.averageScoreChange} suffix=" 分" />
      </div>
    </motion.div>
  );
}

function FormMessage({type, text}: {type: 'success' | 'error'; text: string}) {
  return (
    <div
      className={`flex items-center gap-2 text-sm rounded-lg px-3 py-2 ${
        type === 'success'
          ? 'bg-emerald-50 dark:bg-emerald-900/30 text-emerald-600 dark:text-emerald-400'
          : 'bg-rose-50 dark:bg-rose-900/30 text-rose-600 dark:text-rose-400'
      }`}
    >
      {type === 'success'
        ? <CheckCircle className="w-4 h-4" />
        : <AlertCircle className="w-4 h-4" />}
      <span>{text}</span>
    </div>
  );
}

function WeaknessRow({item}: {item: WeaknessTrend}) {
  const score = item.averageScore ?? 0;
  return (
    <div className="flex items-center gap-4 py-4 border-b border-slate-100 dark:border-slate-700 last:border-0">
      <div className="flex-1 min-w-0">
        <div className="flex items-center justify-between gap-3 mb-2">
          <p className="font-medium text-slate-800 dark:text-white truncate">{item.item}</p>
          <span className="text-sm font-semibold text-slate-700 dark:text-slate-200">
            {formatScore(item.averageScore)} 分
          </span>
        </div>
        <div className="h-2 bg-slate-100 dark:bg-slate-700 rounded-full overflow-hidden">
          <div
            className="h-full rounded-full bg-amber-500"
            style={{width: `${Math.max(0, Math.min(score, 100))}%`}}
          />
        </div>
      </div>
      <div className="w-32 flex flex-col items-end gap-1">
        <DeltaBadge value={item.averageScoreChange} suffix=" 分" />
        <span className="text-xs text-slate-400 dark:text-slate-500">{item.sampleCount} 题样本</span>
      </div>
    </div>
  );
}

function ModuleHeader({
  icon: Icon,
  title,
  description,
}: {
  icon: React.ComponentType<{className?: string}>;
  title: string;
  description: string;
}) {
  return (
    <div className="mb-5 flex items-center gap-3">
      <div className="flex h-11 w-11 items-center justify-center rounded-xl bg-primary-50 text-primary-600 dark:bg-primary-900/30 dark:text-primary-400">
        <Icon className="h-5 w-5" />
      </div>
      <div>
        <h2 className="text-lg font-semibold text-slate-800 dark:text-white">{title}</h2>
        <p className="mt-1 text-sm text-slate-500 dark:text-slate-400">{description}</p>
      </div>
    </div>
  );
}

export default function ProfilePage() {
  const {user, isAdmin, refreshMe, logout} = useAuth();
  const navigate = useNavigate();
  const [stats, setStats] = useState<ProfileStats | null>(null);
  const [loading, setLoading] = useState(true);
  const [displayName, setDisplayName] = useState(user?.displayName ?? '');
  const [savingName, setSavingName] = useState(false);
  const [nameMessage, setNameMessage] = useState<{type: 'success' | 'error'; text: string} | null>(null);
  const [currentPassword, setCurrentPassword] = useState('');
  const [newPassword, setNewPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const [savingPassword, setSavingPassword] = useState(false);
  const [passwordMessage, setPasswordMessage] = useState<{type: 'success' | 'error'; text: string} | null>(null);

  useEffect(() => {
    setDisplayName(user?.displayName ?? '');
  }, [user?.displayName]);

  useEffect(() => {
    if (isAdmin) {
      setStats(null);
      setLoading(false);
      return;
    }

    let mounted = true;
    setLoading(true);
    profileApi.getStats()
      .then((data) => {
        if (mounted) setStats(data);
      })
      .catch(() => {
        if (mounted) setStats(null);
      })
      .finally(() => {
        if (mounted) setLoading(false);
      });
    return () => {
      mounted = false;
    };
  }, [isAdmin]);

  const chartData = useMemo(() => {
    return (stats?.growthTrend ?? []).map((point) => ({
      ...point,
      label: formatDateOnly(point.date).slice(5),
      averageScore: point.averageScore ?? null,
    }));
  }, [stats]);

  const weakBarData = useMemo(() => {
    return (stats?.weakItems ?? []).map((item) => ({
      item: item.item,
      score: item.averageScore ?? 0,
    }));
  }, [stats]);
  const weakItems = stats?.weakItems ?? [];
  const last7Days = stats?.last7Days ?? EMPTY_PERIOD;
  const last30Days = stats?.last30Days ?? EMPTY_PERIOD;

  const handleNameSubmit = async (event: React.FormEvent) => {
    event.preventDefault();
    const nextName = displayName.trim();
    if (!nextName) {
      setNameMessage({type: 'error', text: '昵称不能为空'});
      return;
    }
    setSavingName(true);
    setNameMessage(null);
    try {
      await profileApi.updateDisplayName(nextName);
      await refreshMe();
      setNameMessage({type: 'success', text: '昵称已更新'});
    } catch (error) {
      setNameMessage({type: 'error', text: error instanceof Error ? error.message : '保存失败'});
    } finally {
      setSavingName(false);
    }
  };

  const handlePasswordSubmit = async (event: React.FormEvent) => {
    event.preventDefault();
    if (newPassword.length < 6) {
      setPasswordMessage({type: 'error', text: '新密码长度至少为 6 位'});
      return;
    }
    if (newPassword !== confirmPassword) {
      setPasswordMessage({type: 'error', text: '两次输入的新密码不一致'});
      return;
    }
    setSavingPassword(true);
    setPasswordMessage(null);
    try {
      await profileApi.updatePassword(currentPassword, newPassword);
      setPasswordMessage({type: 'success', text: '密码已更新，请重新登录'});
      window.setTimeout(() => {
        logout();
        navigate('/login', {replace: true});
      }, 900);
    } catch (error) {
      setPasswordMessage({type: 'error', text: error instanceof Error ? error.message : '修改失败'});
      setSavingPassword(false);
    }
  };

  const pageContainerClass = isAdmin ? 'w-full max-w-5xl mx-auto space-y-8' : 'w-full space-y-8';
  const accountModuleGridClass = 'grid grid-cols-1 gap-6 xl:grid-cols-2';

  return (
    <motion.div className={pageContainerClass} initial={{opacity: 0}} animate={{opacity: 1}}>
      <div className="flex items-start justify-between gap-6 flex-wrap">
        <div>
          <motion.h1
            className="text-2xl font-bold text-slate-800 dark:text-white flex items-center gap-3"
            initial={{opacity: 0, x: -18}}
            animate={{opacity: 1, x: 0}}
          >
            <UserRound className="w-7 h-7 text-primary-500" />
            个人中心
          </motion.h1>
          <p className="text-slate-500 dark:text-slate-400 mt-1">
            {user?.username} · {user?.role === 'ADMIN' ? '管理员' : '普通用户'}
          </p>
        </div>

        <div className="flex items-center gap-3 px-4 py-3 rounded-xl bg-white dark:bg-slate-800 border border-slate-100 dark:border-slate-700 shadow-sm">
          <div className="w-10 h-10 rounded-xl bg-primary-50 dark:bg-primary-900/30 text-primary-600 dark:text-primary-400 flex items-center justify-center">
            <ShieldCheck className="w-5 h-5" />
          </div>
          <div>
            <p className="text-sm font-semibold text-slate-800 dark:text-white">账号安全</p>
            <p className="text-xs text-slate-400 dark:text-slate-500">
              上次登录 {formatDateOnly(user?.lastLoginAt)}
            </p>
          </div>
        </div>
      </div>

      <section>
        <ModuleHeader
          icon={UserRound}
          title="账号资料"
          description="管理昵称、登录密码和账号安全信息"
        />

        <div className={accountModuleGridClass}>
          <motion.form
            onSubmit={handleNameSubmit}
            className="bg-white dark:bg-slate-800 rounded-xl p-6 border border-slate-100 dark:border-slate-700 shadow-sm"
            initial={{opacity: 0, y: 18}}
            animate={{opacity: 1, y: 0}}
          >
            <div className="flex items-center gap-3 mb-5">
              <div className="w-10 h-10 rounded-xl bg-slate-100 dark:bg-slate-700 flex items-center justify-center text-slate-500 dark:text-slate-300">
                <UserRound className="w-5 h-5" />
              </div>
              <div>
                <h3 className="text-base font-semibold text-slate-800 dark:text-white">基础资料</h3>
                <p className="text-xs text-slate-400 dark:text-slate-500">昵称会显示在侧边栏和账号信息中</p>
              </div>
            </div>

            <label className="block text-sm font-medium text-slate-600 dark:text-slate-300 mb-2">
              昵称
            </label>
            <input
              value={displayName}
              onChange={(event) => setDisplayName(event.target.value)}
              maxLength={20}
              className="w-full px-4 py-3 rounded-xl bg-slate-50 dark:bg-slate-900 border border-slate-200 dark:border-slate-700 text-slate-800 dark:text-white outline-none focus:border-primary-500 focus:ring-2 focus:ring-primary-100 dark:focus:ring-primary-900/30"
            />

            {nameMessage && <div className="mt-4"><FormMessage {...nameMessage} /></div>}

            <button
              type="submit"
              disabled={savingName || displayName.trim() === user?.displayName}
              className="mt-5 w-full inline-flex items-center justify-center gap-2 px-4 py-3 rounded-xl bg-primary-500 text-white font-medium hover:bg-primary-600 disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
            >
              {savingName ? <Loader2 className="w-4 h-4 animate-spin" /> : <Save className="w-4 h-4" />}
              保存昵称
            </button>
          </motion.form>

          <motion.form
            onSubmit={handlePasswordSubmit}
            className="bg-white dark:bg-slate-800 rounded-xl p-6 border border-slate-100 dark:border-slate-700 shadow-sm"
            initial={{opacity: 0, y: 18}}
            animate={{opacity: 1, y: 0}}
            transition={{delay: 0.05}}
          >
            <div className="flex items-center gap-3 mb-5">
              <div className="w-10 h-10 rounded-xl bg-slate-100 dark:bg-slate-700 flex items-center justify-center text-slate-500 dark:text-slate-300">
                <KeyRound className="w-5 h-5" />
              </div>
              <div>
                <h3 className="text-base font-semibold text-slate-800 dark:text-white">登录密码</h3>
                <p className="text-xs text-slate-400 dark:text-slate-500">修改后需要重新登录</p>
              </div>
            </div>

            <div className="space-y-4">
              {[
                {label: '当前密码', value: currentPassword, setter: setCurrentPassword, autoComplete: 'current-password'},
                {label: '新密码', value: newPassword, setter: setNewPassword, autoComplete: 'new-password'},
                {label: '确认新密码', value: confirmPassword, setter: setConfirmPassword, autoComplete: 'new-password'},
              ].map((field) => (
                <label key={field.label} className="block">
                  <span className="block text-sm font-medium text-slate-600 dark:text-slate-300 mb-2">
                    {field.label}
                  </span>
                  <div className="relative">
                    <Lock className="w-4 h-4 text-slate-400 absolute left-4 top-1/2 -translate-y-1/2" />
                    <input
                      type="password"
                      value={field.value}
                      onChange={(event) => field.setter(event.target.value)}
                      autoComplete={field.autoComplete}
                      className="w-full pl-11 pr-4 py-3 rounded-xl bg-slate-50 dark:bg-slate-900 border border-slate-200 dark:border-slate-700 text-slate-800 dark:text-white outline-none focus:border-primary-500 focus:ring-2 focus:ring-primary-100 dark:focus:ring-primary-900/30"
                    />
                  </div>
                </label>
              ))}
            </div>

            {passwordMessage && <div className="mt-4"><FormMessage {...passwordMessage} /></div>}

            <button
              type="submit"
              disabled={savingPassword || !currentPassword || !newPassword || !confirmPassword}
              className="mt-5 w-full inline-flex items-center justify-center gap-2 px-4 py-3 rounded-xl bg-slate-900 dark:bg-primary-500 text-white font-medium hover:bg-slate-800 dark:hover:bg-primary-600 disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
            >
              {savingPassword ? <Loader2 className="w-4 h-4 animate-spin" /> : <KeyRound className="w-4 h-4" />}
              修改密码
            </button>
          </motion.form>
        </div>
      </section>

      {!isAdmin && (
        <section>
          <ModuleHeader
            icon={TrendingUp}
            title="成长趋势"
            description="查看近期面试练习、平均分和薄弱项变化"
          />

          <div className="space-y-6">
          {loading ? (
            <div className="flex items-center justify-center min-h-[360px] bg-white dark:bg-slate-800 rounded-xl border border-slate-100 dark:border-slate-700">
              <Loader2 className="w-8 h-8 text-primary-500 animate-spin" />
            </div>
          ) : stats ? (
            <>
              <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
                <PeriodCard metric={last7Days} label="最近 7 天" />
                <PeriodCard metric={last30Days} label="最近 30 天" />
              </div>

              <motion.div
                className="bg-white dark:bg-slate-800 rounded-xl p-6 border border-slate-100 dark:border-slate-700 shadow-sm"
                initial={{opacity: 0, y: 18}}
                animate={{opacity: 1, y: 0}}
                transition={{delay: 0.08}}
              >
                <div className="flex items-center justify-between gap-4 mb-6">
                  <div>
                    <h2 className="text-base font-semibold text-slate-800 dark:text-white">成长趋势</h2>
                    <p className="text-xs text-slate-400 dark:text-slate-500 mt-1">最近 30 天面试次数与平均分</p>
                  </div>
                  <TrendingUp className="w-5 h-5 text-primary-500" />
                </div>
                <div className="h-72 min-w-0">
                  {chartData.length > 0 ? (
                    <ResponsiveContainer width="100%" height="100%">
                      <AreaChart data={chartData} margin={{top: 8, right: 16, left: -16, bottom: 0}}>
                        <CartesianGrid strokeDasharray="3 3" stroke="#e2e8f0" className="dark:stroke-slate-700" />
                        <XAxis dataKey="label" tick={{fill: '#94a3b8', fontSize: 12}} axisLine={false} tickLine={false} />
                        <YAxis yAxisId="score" domain={[0, 100]} tick={{fill: '#94a3b8', fontSize: 12}} axisLine={false} tickLine={false} />
                        <YAxis yAxisId="count" orientation="right" allowDecimals={false} tick={{fill: '#94a3b8', fontSize: 12}} axisLine={false} tickLine={false} />
                        <Tooltip
                          contentStyle={{
                            backgroundColor: '#fff',
                            border: '1px solid #e2e8f0',
                            borderRadius: 12,
                            boxShadow: '0 12px 24px rgba(15,23,42,0.12)',
                          }}
                          formatter={(value, name) => [
                            name === 'averageScore' ? `${value} 分` : `${value} 场`,
                            name === 'averageScore' ? '平均分' : '面试次数',
                          ]}
                        />
                        <Area
                          yAxisId="score"
                          type="monotone"
                          dataKey="averageScore"
                          stroke="#6366f1"
                          fill="#6366f1"
                          fillOpacity={0.14}
                          strokeWidth={3}
                          connectNulls
                        />
                        <Bar yAxisId="count" dataKey="interviewCount" fill="#14b8a6" radius={[6, 6, 0, 0]} />
                      </AreaChart>
                    </ResponsiveContainer>
                  ) : (
                    <div className="h-full flex items-center justify-center rounded-xl border border-dashed border-slate-200 dark:border-slate-700 text-slate-400 dark:text-slate-500">
                      暂无趋势数据
                    </div>
                  )}
                </div>
              </motion.div>

              <div className="grid grid-cols-1 2xl:grid-cols-[1fr_360px] gap-6">
                <motion.div
                  className="bg-white dark:bg-slate-800 rounded-xl p-6 border border-slate-100 dark:border-slate-700 shadow-sm"
                  initial={{opacity: 0, y: 18}}
                  animate={{opacity: 1, y: 0}}
                  transition={{delay: 0.12}}
                >
                  <div className="flex items-center justify-between gap-4 mb-2">
                    <div>
                      <h2 className="text-base font-semibold text-slate-800 dark:text-white">薄弱项变化</h2>
                      <p className="text-xs text-slate-400 dark:text-slate-500 mt-1">按最近 30 天题目类别均分排序</p>
                    </div>
                    <TrendingDown className="w-5 h-5 text-amber-500" />
                  </div>
                  {weakItems.length > 0 ? (
                    <div className="mt-2">
                      {weakItems.map((item) => <WeaknessRow key={item.item} item={item} />)}
                    </div>
                  ) : (
                    <div className="py-14 text-center text-slate-400 dark:text-slate-500">
                      暂无可分析的答题评分
                    </div>
                  )}
                </motion.div>

                <motion.div
                  className="bg-white dark:bg-slate-800 rounded-xl p-6 border border-slate-100 dark:border-slate-700 shadow-sm"
                  initial={{opacity: 0, y: 18}}
                  animate={{opacity: 1, y: 0}}
                  transition={{delay: 0.16}}
                >
                  <h2 className="text-base font-semibold text-slate-800 dark:text-white mb-6">类别均分</h2>
                  <div className="h-64 min-w-0">
                    {weakBarData.length > 0 ? (
                      <ResponsiveContainer width="100%" height="100%">
                        <BarChart data={weakBarData} layout="vertical" margin={{top: 0, right: 12, left: 8, bottom: 0}}>
                          <CartesianGrid strokeDasharray="3 3" horizontal={false} stroke="#e2e8f0" className="dark:stroke-slate-700" />
                          <XAxis type="number" domain={[0, 100]} tick={{fill: '#94a3b8', fontSize: 12}} axisLine={false} tickLine={false} />
                          <YAxis type="category" dataKey="item" width={76} tick={{fill: '#94a3b8', fontSize: 12}} axisLine={false} tickLine={false} />
                          <Tooltip
                            contentStyle={{
                              backgroundColor: '#fff',
                              border: '1px solid #e2e8f0',
                              borderRadius: 12,
                            }}
                            formatter={(value) => [`${value} 分`, '平均分']}
                          />
                          <Bar dataKey="score" fill="#f59e0b" radius={[0, 6, 6, 0]} barSize={14} />
                        </BarChart>
                      </ResponsiveContainer>
                    ) : (
                      <div className="h-full flex items-center justify-center rounded-xl border border-dashed border-slate-200 dark:border-slate-700 text-slate-400 dark:text-slate-500">
                        暂无类别数据
                      </div>
                    )}
                  </div>
                </motion.div>
              </div>
            </>
          ) : (
            <div className="py-20 text-center bg-white dark:bg-slate-800 rounded-xl border border-slate-100 dark:border-slate-700 text-slate-500 dark:text-slate-400">
              统计数据加载失败
            </div>
          )}
          </div>
        </section>
      )}
    </motion.div>
  );
}
