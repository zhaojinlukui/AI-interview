import { useEffect, useMemo, useState, type ComponentType, type ReactNode } from 'react';
import {
  ArrowLeft,
  ChevronRight,
  CheckCircle,
  Download,
  FileText,
  Loader2,
  Lock,
  MessageSquareText,
  Power,
  PowerOff,
  RefreshCw,
  Save,
  Search,
  Shield,
  Trash2,
  UserCog,
} from 'lucide-react';
import { adminApi } from '../api/admin';
import type { InterviewDetail } from '../api/history';
import type { VoiceEvaluationDetail } from '../api/voiceInterview';
import type {
  AdminInterviewItem,
  AdminResumeDetail,
  AdminResumeListItem,
  AdminUser,
  AdminVoiceInterviewDetail,
  SystemAiParameters,
} from '../types/admin';
import AnalysisPanel from '../components/AnalysisPanel';
import ConfirmDialog from '../components/ConfirmDialog';
import InterviewDetailPanel from '../components/InterviewDetailPanel';
import { formatDateTimeWithSeconds } from '../utils/date';

const MIN_PASSWORD_LENGTH = 6;
const DISPLAYED_ROLE: AdminUser['role'] = 'USER';

const DEFAULT_RESUME_WEIGHTS: SystemAiParameters['resumeWeights'] = {
  projectWeight: 40,
  skillMatchWeight: 20,
  contentWeight: 15,
  structureWeight: 15,
  expressionWeight: 10,
};

type AdminTab = 'account' | 'resumes' | 'interviews';
type UserAction = { type: 'enable' | 'disable' | 'delete'; user: AdminUser };

export default function AdminUsersPage() {
  const [users, setUsers] = useState<AdminUser[]>([]);
  const [selectedUserId, setSelectedUserId] = useState<number | null>(null);
  const [activeTab, setActiveTab] = useState<AdminTab>('account');
  const [query, setQuery] = useState('');
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [pendingAction, setPendingAction] = useState<UserAction | null>(null);
  const [actionLoading, setActionLoading] = useState(false);

  const selectedUser = users.find((user) => user.id === selectedUserId) ?? null;

  const loadUsers = async () => {
    setLoading(true);
    setError('');
    try {
      const data = await adminApi.listUsers();
      const userAccounts = data.filter((user) => user.role === DISPLAYED_ROLE);
      setUsers(userAccounts);
      setSelectedUserId((current) =>
        userAccounts.some((user) => user.id === current) ? current : null
      );
    } catch (err) {
      setError(err instanceof Error ? err.message : '加载用户失败');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    loadUsers();
  }, []);

  const filteredUsers = useMemo(() => {
    const keyword = query.trim().toLowerCase();
    if (!keyword) return users;
    return users.filter((user) =>
      [user.username, user.displayName].some((value) => value.toLowerCase().includes(keyword))
    );
  }, [query, users]);

  const selectUser = (userId: number) => {
    setSelectedUserId(userId);
    setActiveTab('account');
  };

  const backToUsers = () => {
    setSelectedUserId(null);
    setActiveTab('account');
  };

  const requestToggleEnabled = (user: AdminUser) => {
    setPendingAction({ type: user.enabled ? 'disable' : 'enable', user });
  };

  const requestDeleteUser = (user: AdminUser) => {
    setPendingAction({ type: 'delete', user });
  };

  const confirmUserAction = async () => {
    if (!pendingAction) return;
    setActionLoading(true);
    setError('');
    try {
      if (pendingAction.type === 'delete') {
        await adminApi.deleteUser(pendingAction.user.id);
      } else {
        await adminApi.updateEnabled(pendingAction.user.id, pendingAction.type === 'enable');
      }
      await loadUsers();
      setPendingAction(null);
    } catch (err) {
      setError(err instanceof Error ? err.message : '账号操作失败');
    } finally {
      setActionLoading(false);
    }
  };

  return (
    <div className="space-y-6">
      <header className="flex items-center justify-between gap-4">
        <div>
          <h1 className="text-2xl font-bold text-slate-900 dark:text-white">管理员端</h1>
          <p className="mt-1 text-sm text-slate-500 dark:text-slate-400">
            集中查看用户账号信息、简历报告与面试结果
          </p>
        </div>
        <button
          onClick={loadUsers}
          className="btn-secondary flex h-10 items-center gap-2 rounded-xl px-4"
        >
          <RefreshCw className="h-4 w-4" />
          刷新
        </button>
      </header>

      {error && (
        <div className="rounded-xl border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-700 dark:border-red-900 dark:bg-red-950/30 dark:text-red-300">
          {error}
        </div>
      )}

      {selectedUser ? (
        <section className="min-w-0 space-y-4">
          <div className="dark-card p-4">
            <div className="flex flex-wrap items-center justify-between gap-3">
              <div className="flex items-center gap-3">
                <button
                  onClick={backToUsers}
                  className="btn-secondary flex h-10 w-10 items-center justify-center rounded-xl"
                  aria-label="返回用户列表"
                >
                  <ArrowLeft className="h-4 w-4" />
                </button>
                <div className="flex h-11 w-11 items-center justify-center rounded-xl bg-primary-500/10 text-primary-600 dark:text-primary-300">
                  <Shield className="h-5 w-5" />
                </div>
                <div>
                  <h2 className="text-lg font-semibold text-slate-900 dark:text-white">
                    {selectedUser.displayName}
                  </h2>
                  <p className="text-sm text-slate-500 dark:text-slate-400">
                    {selectedUser.username} · {formatRole(selectedUser.role)}
                  </p>
                </div>
              </div>
              <div className="flex flex-wrap items-center justify-end gap-2">
                <StatusBadge enabled={selectedUser.enabled} />
                <UserActionButtons
                  user={selectedUser}
                  onToggle={requestToggleEnabled}
                  onDelete={requestDeleteUser}
                />
              </div>
            </div>
          </div>

          <div className="dark-card p-2">
            <div className="grid grid-cols-1 gap-2 md:grid-cols-3">
              <TabButton
                active={activeTab === 'account'}
                icon={UserCog}
                label="账号资料"
                onClick={() => setActiveTab('account')}
              />
              <TabButton
                active={activeTab === 'resumes'}
                icon={FileText}
                label="简历报告"
                onClick={() => setActiveTab('resumes')}
              />
              <TabButton
                active={activeTab === 'interviews'}
                icon={MessageSquareText}
                label="面试结果"
                onClick={() => setActiveTab('interviews')}
              />
            </div>
          </div>

          {activeTab === 'account' && (
            <AccountPanel
              user={selectedUser}
              onSaved={loadUsers}
              onToggle={requestToggleEnabled}
              onDelete={requestDeleteUser}
            />
          )}
          {activeTab === 'resumes' && <ResumesTab userId={selectedUser.id} />}
          {activeTab === 'interviews' && <InterviewsTab userId={selectedUser.id} />}
        </section>
      ) : (
        <section className="dark-card overflow-hidden">
          <div className="flex flex-col gap-4 border-b border-slate-200/70 p-4 dark:border-slate-700/70 lg:flex-row lg:items-center lg:justify-between">
            <div>
              <h2 className="text-lg font-semibold text-slate-900 dark:text-white">用户基本情况</h2>
              <p className="mt-1 text-sm text-slate-500 dark:text-slate-400">
                共 {users.length} 位用户，点击任意用户查看账号、简历与面试详情
              </p>
            </div>
            <div className="flex w-full items-center gap-2 rounded-xl bg-slate-100 px-3 py-2 dark:bg-slate-900 lg:max-w-sm">
              <Search className="h-4 w-4 text-slate-400" />
              <input
                value={query}
                onChange={(event) => setQuery(event.target.value)}
                placeholder="搜索用户名或昵称"
                className="w-full bg-transparent text-sm text-slate-900 outline-none placeholder:text-slate-400 dark:text-slate-100"
              />
            </div>
          </div>

          <div className="overflow-x-auto">
            {loading ? (
              <div className="flex h-48 items-center justify-center">
                <Loader2 className="h-6 w-6 animate-spin text-primary-500" />
              </div>
            ) : filteredUsers.length === 0 ? (
              <div className="p-8 text-center text-sm text-slate-500">暂无用户</div>
            ) : (
              <table className="w-full min-w-[1080px] text-left">
                <thead className="bg-slate-50 text-xs uppercase text-slate-500 dark:bg-slate-900 dark:text-slate-400">
                  <tr>
                    <th className="px-4 py-3 font-medium">用户</th>
                    <th className="px-4 py-3 font-medium">状态</th>
                    <th className="px-4 py-3 font-medium">简历</th>
                    <th className="px-4 py-3 font-medium">文字面试</th>
                    <th className="px-4 py-3 font-medium">语音面试</th>
                    <th className="px-4 py-3 font-medium">最后登录</th>
                    <th className="px-4 py-3 font-medium">最近活动</th>
                    <th className="px-4 py-3 font-medium">操作</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-slate-100 dark:divide-slate-800">
                  {filteredUsers.map((user) => (
                    <tr
                      key={user.id}
                      onClick={() => selectUser(user.id)}
                      onKeyDown={(event) => {
                        if (event.key === 'Enter' || event.key === ' ') {
                          event.preventDefault();
                          selectUser(user.id);
                        }
                      }}
                      tabIndex={0}
                      className="cursor-pointer transition-colors hover:bg-slate-50 focus:bg-slate-50 focus:outline-none dark:hover:bg-slate-800/60 dark:focus:bg-slate-800/60"
                    >
                      <td className="px-4 py-4">
                        <div className="flex items-center gap-3">
                          <div className="flex h-10 w-10 shrink-0 items-center justify-center rounded-xl bg-primary-500/10 font-semibold text-primary-600 dark:text-primary-300">
                            {user.displayName.slice(0, 1).toUpperCase()}
                          </div>
                          <div className="min-w-0">
                            <div className="truncate font-medium text-slate-900 dark:text-white">
                              {user.displayName}
                            </div>
                            <div className="truncate text-xs text-slate-500">{user.username}</div>
                          </div>
                        </div>
                      </td>
                      <td className="px-4 py-4">
                        <StatusBadge enabled={user.enabled} />
                      </td>
                      <td className="px-4 py-4 text-sm text-slate-600 dark:text-slate-300">
                        {user.resumeCount}
                      </td>
                      <td className="px-4 py-4 text-sm text-slate-600 dark:text-slate-300">
                        {user.textInterviewCount}
                      </td>
                      <td className="px-4 py-4 text-sm text-slate-600 dark:text-slate-300">
                        {user.voiceInterviewCount}
                      </td>
                      <td className="px-4 py-4 text-sm text-slate-500 dark:text-slate-400">
                        {formatNullableDate(user.lastLoginAt)}
                      </td>
                      <td className="px-4 py-4 text-sm text-slate-500 dark:text-slate-400">
                        {formatNullableDate(user.recentActivityAt)}
                      </td>
                      <td
                        className="px-4 py-4"
                        onClick={(event) => event.stopPropagation()}
                        onKeyDown={(event) => event.stopPropagation()}
                      >
                        <div className="flex items-center justify-end gap-2">
                          <UserActionButtons
                            user={user}
                            onToggle={requestToggleEnabled}
                            onDelete={requestDeleteUser}
                          />
                          <button
                            onClick={() => selectUser(user.id)}
                            className="flex h-9 w-9 items-center justify-center rounded-lg text-slate-400 transition-colors hover:bg-slate-100 hover:text-slate-700 dark:hover:bg-slate-800 dark:hover:text-slate-200"
                            title="查看详情"
                            aria-label="查看详情"
                          >
                            <ChevronRight className="h-4 w-4" />
                          </button>
                        </div>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </div>
        </section>
      )}
      <ConfirmDialog
        open={Boolean(pendingAction)}
        title={getUserActionTitle(pendingAction)}
        message={getUserActionMessage(pendingAction)}
        confirmText={getUserActionConfirmText(pendingAction)}
        confirmVariant={getUserActionVariant(pendingAction)}
        loading={actionLoading}
        onConfirm={confirmUserAction}
        onCancel={() => {
          if (!actionLoading) {
            setPendingAction(null);
          }
        }}
      />
    </div>
  );
}

function AccountPanel({
  user,
  onSaved,
  onToggle,
  onDelete,
}: {
  user: AdminUser;
  onSaved: () => Promise<void>;
  onToggle: (user: AdminUser) => void;
  onDelete: (user: AdminUser) => void;
}) {
  const [displayName, setDisplayName] = useState(user.displayName);
  const [password, setPassword] = useState('');
  const [savingName, setSavingName] = useState(false);
  const [savingPassword, setSavingPassword] = useState(false);
  const [message, setMessage] = useState('');
  const [successToast, setSuccessToast] = useState('');

  useEffect(() => {
    setDisplayName(user.displayName);
    setPassword('');
    setMessage('');
    setSuccessToast('');
  }, [user.id, user.displayName]);

  useEffect(() => {
    if (!successToast) return undefined;
    const timer = window.setTimeout(() => setSuccessToast(''), 2400);
    return () => window.clearTimeout(timer);
  }, [successToast]);

  const showSuccessToast = (text: string) => {
    setMessage('');
    setSuccessToast(text);
  };

  const saveDisplayName = async () => {
    if (!displayName.trim()) return;
    setSavingName(true);
    setMessage('');
    setSuccessToast('');
    try {
      await adminApi.updateDisplayName(user.id, displayName.trim());
      await onSaved();
      showSuccessToast('昵称修改成功');
    } catch (err) {
      setMessage(err instanceof Error ? err.message : '保存昵称失败');
    } finally {
      setSavingName(false);
    }
  };

  const savePassword = async () => {
    if (password.length < MIN_PASSWORD_LENGTH) {
      setMessage('密码长度至少 6 位');
      return;
    }
    setSavingPassword(true);
    setMessage('');
    setSuccessToast('');
    try {
      await adminApi.updatePassword(user.id, password);
      setPassword('');
      await onSaved();
      showSuccessToast('密码修改成功，用户现有登录状态将失效');
    } catch (err) {
      setMessage(err instanceof Error ? err.message : '修改密码失败');
    } finally {
      setSavingPassword(false);
    }
  };

  return (
    <div className="dark-card p-6">
      <SuccessToast message={successToast} />
      <div className="grid gap-6 lg:grid-cols-2">
        <div>
          <h3 className="mb-4 flex items-center gap-2 font-semibold text-slate-900 dark:text-white">
            <UserCog className="h-5 w-5 text-primary-500" />
            账号资料
          </h3>
          <div className="space-y-4">
            <InfoRow label="用户 ID" value={String(user.id)} />
            <InfoRow label="用户名" value={user.username} />
            <InfoRow label="昵称" value={user.displayName} />
            <InfoRow label="角色" value={formatRole(user.role)} />
            <InfoRow label="状态" value={user.enabled ? '启用' : '禁用'} />
            <InfoRow label="创建时间" value={formatNullableDate(user.createdAt)} />
            <InfoRow label="最后登录" value={formatNullableDate(user.lastLoginAt)} />
          </div>
        </div>

        <div className="space-y-5">
          <label className="block">
            <span className="mb-2 block text-sm font-medium text-slate-700 dark:text-slate-300">
              昵称
            </span>
            <div className="flex gap-3">
              <input
                value={displayName}
                onChange={(event) => setDisplayName(event.target.value)}
                className="dark-input h-11 min-w-0 flex-1 rounded-xl px-4"
              />
              <button
                onClick={saveDisplayName}
                disabled={savingName || !displayName.trim()}
                className="btn-primary flex h-11 items-center gap-2 rounded-xl px-4 disabled:opacity-60"
              >
                <Save className="h-4 w-4" />
                保存
              </button>
            </div>
          </label>

          <label className="block">
            <span className="mb-2 block text-sm font-medium text-slate-700 dark:text-slate-300">
              新密码
            </span>
            <div className="flex gap-3">
              <input
                value={password}
                onChange={(event) => setPassword(event.target.value)}
                type="password"
                className="dark-input h-11 min-w-0 flex-1 rounded-xl px-4"
                placeholder="至少 6 位"
              />
              <button
                onClick={savePassword}
                disabled={savingPassword || !password}
                className="btn-secondary flex h-11 items-center gap-2 rounded-xl px-4 disabled:opacity-60"
              >
                <Lock className="h-4 w-4" />
                修改
              </button>
            </div>
          </label>

          <div className="border-t border-slate-200 pt-5 dark:border-slate-800">
            <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
              <div>
                <h4 className="text-sm font-semibold text-slate-900 dark:text-white">账号控制</h4>
                <p className="mt-1 text-xs text-slate-500 dark:text-slate-400">
                  禁用会让用户无法登录，删除账号后不可撤销
                </p>
              </div>
              <div className="flex flex-wrap gap-2">
                <button
                  onClick={() => onToggle(user)}
                  className={`flex h-10 items-center gap-2 rounded-xl px-4 text-sm font-medium transition-colors ${
                    user.enabled
                      ? 'border border-amber-200 bg-amber-50 text-amber-700 hover:bg-amber-100 dark:border-amber-900/70 dark:bg-amber-950/30 dark:text-amber-300 dark:hover:bg-amber-950/50'
                      : 'btn-primary'
                  }`}
                >
                  {user.enabled ? <PowerOff className="h-4 w-4" /> : <Power className="h-4 w-4" />}
                  {user.enabled ? '禁用账号' : '启用账号'}
                </button>
                <button
                  onClick={() => onDelete(user)}
                  className="flex h-10 items-center gap-2 rounded-xl border border-red-200 bg-red-50 px-4 text-sm font-medium text-red-700 transition-colors hover:bg-red-100 dark:border-red-900/70 dark:bg-red-950/30 dark:text-red-300 dark:hover:bg-red-950/50"
                >
                  <Trash2 className="h-4 w-4" />
                  删除账号
                </button>
              </div>
            </div>
          </div>

          {message && <p className="text-sm text-slate-500 dark:text-slate-400">{message}</p>}
        </div>
      </div>
    </div>
  );
}

function SuccessToast({ message }: { message: string }) {
  if (!message) return null;

  return (
    <div
      role="status"
      aria-live="polite"
      className="fixed left-4 right-4 top-4 z-50 rounded-xl border border-emerald-200 bg-white px-4 py-3 text-sm text-emerald-700 shadow-lg shadow-slate-900/10 dark:border-emerald-900/70 dark:bg-slate-900 dark:text-emerald-300 sm:left-auto sm:w-[360px]"
    >
      <div className="flex items-start gap-3">
        <CheckCircle className="mt-0.5 h-5 w-5 shrink-0" />
        <div>
          <p className="font-semibold">修改成功</p>
          <p className="mt-0.5 text-emerald-600 dark:text-emerald-400">{message}</p>
        </div>
      </div>
    </div>
  );
}

function UserActionButtons({
  user,
  onToggle,
  onDelete,
}: {
  user: AdminUser;
  onToggle: (user: AdminUser) => void;
  onDelete: (user: AdminUser) => void;
}) {
  const ToggleIcon = user.enabled ? PowerOff : Power;
  return (
    <div className="flex items-center gap-2">
      <button
        onClick={() => onToggle(user)}
        className={`flex h-9 w-9 items-center justify-center rounded-lg transition-colors ${
          user.enabled
            ? 'text-amber-600 hover:bg-amber-50 dark:text-amber-300 dark:hover:bg-amber-950/40'
            : 'text-emerald-600 hover:bg-emerald-50 dark:text-emerald-300 dark:hover:bg-emerald-950/40'
        }`}
        title={user.enabled ? '禁用账号' : '启用账号'}
        aria-label={user.enabled ? '禁用账号' : '启用账号'}
      >
        <ToggleIcon className="h-4 w-4" />
      </button>
      <button
        onClick={() => onDelete(user)}
        className="flex h-9 w-9 items-center justify-center rounded-lg text-red-600 transition-colors hover:bg-red-50 dark:text-red-300 dark:hover:bg-red-950/40"
        title="删除账号"
        aria-label="删除账号"
      >
        <Trash2 className="h-4 w-4" />
      </button>
    </div>
  );
}

function getUserActionTitle(action: UserAction | null) {
  if (!action) return '';
  if (action.type === 'delete') return '删除用户账号';
  return action.type === 'disable' ? '禁用用户账号' : '启用用户账号';
}

function getUserActionMessage(action: UserAction | null) {
  if (!action) return '';
  const name = `${action.user.displayName}（${action.user.username}）`;
  if (action.type === 'delete') {
    return `确定删除用户 ${name}？\n此操作不可撤销，历史报告数据不会随账号自动清理。`;
  }
  if (action.type === 'disable') {
    return `确定禁用用户 ${name}？\n禁用后该用户无法登录，现有登录状态会失效。`;
  }
  return `确定启用用户 ${name}？\n启用后该用户可以重新登录平台。`;
}

function getUserActionConfirmText(action: UserAction | null) {
  if (!action) return '确定';
  if (action.type === 'delete') return '删除';
  return action.type === 'disable' ? '禁用' : '启用';
}

function getUserActionVariant(action: UserAction | null): 'danger' | 'primary' | 'warning' {
  if (action?.type === 'delete') return 'danger';
  if (action?.type === 'disable') return 'warning';
  return 'primary';
}

function ResumesTab({ userId }: { userId: number }) {
  const [resumes, setResumes] = useState<AdminResumeListItem[]>([]);
  const [selectedResume, setSelectedResume] = useState<AdminResumeDetail | null>(null);
  const [scoreMaxima, setScoreMaxima] = useState<SystemAiParameters['resumeWeights']>(
    DEFAULT_RESUME_WEIGHTS
  );
  const [loading, setLoading] = useState(true);
  const [loadingDetail, setLoadingDetail] = useState(false);
  const [exporting, setExporting] = useState(false);

  const loadResumeDetail = async (resumeId: number) => {
    setLoadingDetail(true);
    try {
      setSelectedResume(await adminApi.getResumeDetail(resumeId));
    } finally {
      setLoadingDetail(false);
    }
  };

  const loadResumes = async () => {
    setLoading(true);
    setSelectedResume(null);
    try {
      const data = await adminApi.listUserResumes(userId);
      setResumes(data);
      if (data[0]) {
        await loadResumeDetail(data[0].id);
      }
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    loadResumes();
    adminApi
      .getAiParameters()
      .then((data) => setScoreMaxima(data.resumeWeights))
      .catch(() => setScoreMaxima(DEFAULT_RESUME_WEIGHTS));
  }, [userId]);

  const exportPdf = async () => {
    if (!selectedResume) return;
    setExporting(true);
    try {
      saveBlob(await adminApi.exportResumePdf(selectedResume.id), `resume-${selectedResume.id}.pdf`);
    } finally {
      setExporting(false);
    }
  };

  return (
    <div className="grid gap-4 2xl:grid-cols-[300px_minmax(0,1fr)]">
      <ListPanel title="简历报告" loading={loading} empty={resumes.length === 0}>
        {resumes.map((resume) => (
          <button
            key={resume.id}
            onClick={() => loadResumeDetail(resume.id)}
            className={`w-full rounded-xl p-3 text-left transition-colors ${
              selectedResume?.id === resume.id
                ? 'bg-primary-50 text-primary-700 dark:bg-primary-950/40 dark:text-primary-300'
                : 'hover:bg-slate-100 dark:hover:bg-slate-800'
            }`}
          >
            <div className="truncate text-sm font-medium">{resume.filename}</div>
            <div className="mt-1 flex justify-between text-xs text-slate-500">
              <span>{formatNullableDate(resume.uploadedAt)}</span>
              <span>{resume.latestScore ?? '-'} 分</span>
            </div>
          </button>
        ))}
      </ListPanel>

      <div className="min-w-0">
        {loadingDetail ? (
          <LoadingCard />
        ) : selectedResume ? (
          <AnalysisPanel
            analysis={selectedResume.analyses?.[0]}
            analyzeStatus={selectedResume.analyzeStatus}
            analyzeError={selectedResume.analyzeError}
            onExport={exportPdf}
            exporting={exporting}
            scoreMaxima={scoreMaxima}
          />
        ) : (
          <EmptyCard text="暂无简历报告" />
        )}
      </div>
    </div>
  );
}

function InterviewsTab({ userId }: { userId: number }) {
  const [interviews, setInterviews] = useState<AdminInterviewItem[]>([]);
  const [textDetail, setTextDetail] = useState<InterviewDetail | null>(null);
  const [voiceDetail, setVoiceDetail] = useState<AdminVoiceInterviewDetail | null>(null);
  const [loading, setLoading] = useState(true);
  const [loadingDetail, setLoadingDetail] = useState(false);
  const [exporting, setExporting] = useState(false);

  useEffect(() => {
    const load = async () => {
      setLoading(true);
      setTextDetail(null);
      setVoiceDetail(null);
      try {
        setInterviews(await adminApi.listUserInterviews(userId));
      } finally {
        setLoading(false);
      }
    };
    load();
  }, [userId]);

  const selectInterview = async (item: AdminInterviewItem) => {
    setLoadingDetail(true);
    setTextDetail(null);
    setVoiceDetail(null);
    try {
      if (item.type === 'TEXT') {
        setTextDetail(await adminApi.getTextInterviewDetail(item.sessionId));
      } else {
        setVoiceDetail(await adminApi.getVoiceInterviewDetail(item.id));
      }
    } finally {
      setLoadingDetail(false);
    }
  };

  const exportTextPdf = async () => {
    if (!textDetail) return;
    setExporting(true);
    try {
      saveBlob(
        await adminApi.exportTextInterviewPdf(textDetail.sessionId),
        `interview-${textDetail.sessionId}.pdf`
      );
    } finally {
      setExporting(false);
    }
  };

  const voiceAsTextDetail = voiceDetail?.evaluation
    ? mapVoiceEvaluationToInterviewDetail(voiceDetail.evaluation, voiceDetail)
    : null;

  return (
    <div className="grid gap-4 2xl:grid-cols-[320px_minmax(0,1fr)]">
      <ListPanel title="面试结果" loading={loading} empty={interviews.length === 0}>
        {interviews.map((item) => (
          <button
            key={`${item.type}-${item.id}`}
            onClick={() => selectInterview(item)}
            className="w-full rounded-xl p-3 text-left transition-colors hover:bg-slate-100 dark:hover:bg-slate-800"
          >
            <div className="flex items-center justify-between gap-2">
              <span className="text-sm font-medium">
                {item.type === 'TEXT' ? '文字面试' : '语音面试'}
              </span>
              <span className="text-xs text-slate-500">{item.overallScore ?? '-'} 分</span>
            </div>
            <div className="mt-1 truncate text-xs text-slate-500">
              {item.sessionId} · {item.status || 'UNKNOWN'}
            </div>
            <div className="mt-1 text-xs text-slate-500">{formatNullableDate(item.createdAt)}</div>
          </button>
        ))}
      </ListPanel>

      <div className="min-w-0 space-y-4">
        {loadingDetail ? (
          <LoadingCard />
        ) : textDetail ? (
          <>
            <div className="flex justify-end">
              <button
                onClick={exportTextPdf}
                disabled={exporting}
                className="btn-secondary flex h-10 items-center gap-2 rounded-xl px-4 disabled:opacity-60"
              >
                <Download className="h-4 w-4" />
                {exporting ? '导出中...' : '导出 PDF'}
              </button>
            </div>
            <InterviewDetailPanel interview={textDetail} />
          </>
        ) : voiceAsTextDetail ? (
          <InterviewDetailPanel interview={voiceAsTextDetail} />
        ) : voiceDetail ? (
          <EmptyCard
            text={`语音面试评估尚不可用：${voiceDetail.evaluateStatus || voiceDetail.status || '未评估'}`}
          />
        ) : (
          <EmptyCard text="请选择一场面试" />
        )}
      </div>
    </div>
  );
}

function TabButton({
  active,
  icon: Icon,
  label,
  onClick,
}: {
  active: boolean;
  icon: ComponentType<{ className?: string }>;
  label: string;
  onClick: () => void;
}) {
  return (
    <button
      onClick={onClick}
      className={`flex h-11 items-center justify-center gap-2 rounded-xl text-sm font-medium transition-colors ${
        active
          ? 'bg-primary-50 text-primary-700 dark:bg-primary-950/40 dark:text-primary-300'
          : 'text-slate-600 hover:bg-slate-100 dark:text-slate-400 dark:hover:bg-slate-800'
      }`}
    >
      <Icon className="h-4 w-4" />
      {label}
    </button>
  );
}

function ListPanel({
  title,
  loading,
  empty,
  children,
}: {
  title: string;
  loading: boolean;
  empty: boolean;
  children: ReactNode;
}) {
  return (
    <div className="dark-card min-h-[240px] p-4">
      <h3 className="mb-3 text-sm font-semibold text-slate-900 dark:text-white">{title}</h3>
      {loading ? (
        <LoadingCard compact />
      ) : empty ? (
        <EmptyCard text="暂无数据" compact />
      ) : (
        <div className="space-y-2">{children}</div>
      )}
    </div>
  );
}

function InfoRow({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex justify-between gap-4 border-b border-slate-100 py-2 text-sm dark:border-slate-800">
      <span className="text-slate-500 dark:text-slate-400">{label}</span>
      <span className="text-right font-medium text-slate-900 dark:text-white">{value}</span>
    </div>
  );
}

function StatusBadge({ enabled }: { enabled: boolean }) {
  return (
    <span
      className={`rounded-full border px-3 py-1 text-xs font-medium ${
        enabled
          ? 'border-emerald-200 bg-emerald-50 text-emerald-700 dark:border-emerald-900/70 dark:bg-emerald-950/30 dark:text-emerald-300'
          : 'border-slate-200 bg-slate-50 text-slate-600 dark:border-slate-700 dark:bg-slate-900 dark:text-slate-300'
      }`}
    >
      {enabled ? '已启用' : '已禁用'}
    </span>
  );
}

function LoadingCard({ compact = false }: { compact?: boolean }) {
  return (
    <div className={`dark-card flex items-center justify-center ${compact ? 'h-24' : 'h-56'}`}>
      <Loader2 className="h-6 w-6 animate-spin text-primary-500" />
    </div>
  );
}

function EmptyCard({ text, compact = false }: { text: string; compact?: boolean }) {
  return (
    <div
      className={`dark-card flex items-center justify-center text-sm text-slate-500 dark:text-slate-400 ${
        compact ? 'h-24' : 'h-56'
      }`}
    >
      {text}
    </div>
  );
}

function mapVoiceEvaluationToInterviewDetail(
  evaluation: VoiceEvaluationDetail,
  meta: AdminVoiceInterviewDetail
): InterviewDetail {
  return {
    id: evaluation.sessionId,
    sessionId: String(evaluation.sessionId),
    totalQuestions: evaluation.totalQuestions,
    status: meta.status || 'COMPLETED',
    evaluateStatus: toEvaluateStatus(meta.evaluateStatus),
    evaluateError: meta.evaluateError || undefined,
    overallScore: evaluation.overallScore,
    overallFeedback: evaluation.overallFeedback,
    createdAt: meta.startTime || '',
    completedAt: meta.endTime || '',
    strengths: evaluation.strengths,
    improvements: evaluation.improvements,
    answers: evaluation.answers.map((answer) => ({
      questionIndex: answer.questionIndex,
      question: answer.question,
      category: answer.category,
      userAnswer: answer.userAnswer,
      score: answer.score,
      feedback: answer.feedback,
      referenceAnswer: answer.referenceAnswer ?? undefined,
      keyPoints: answer.keyPoints ?? undefined,
      answeredAt: '',
    })),
  };
}

function toEvaluateStatus(value?: string | null): InterviewDetail['evaluateStatus'] {
  if (value === 'PENDING' || value === 'PROCESSING' || value === 'COMPLETED' || value === 'FAILED') {
    return value;
  }
  return undefined;
}

function saveBlob(blob: Blob, filename: string) {
  const url = window.URL.createObjectURL(blob);
  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.download = filename;
  document.body.appendChild(anchor);
  anchor.click();
  document.body.removeChild(anchor);
  window.URL.revokeObjectURL(url);
}

function formatRole(value: AdminUser['role']) {
  return value === 'ADMIN' ? '管理员' : '用户';
}

function formatNullableDate(value?: string | null) {
  return value ? formatDateTimeWithSeconds(value) : '-';
}
