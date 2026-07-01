import { Link, Outlet, useLocation, useNavigate } from 'react-router-dom';
import { motion } from 'framer-motion';
import {
  BookOpenCheck,
  Calendar,
  ChevronRight,
  Database,
  FileStack,
  LogOut,
  MessageSquare,
  Moon,
  Settings,
  SlidersHorizontal,
  Sparkles,
  Sun,
  UserCog,
  UserRound,
  Users,
} from 'lucide-react';
import { useState } from 'react';
import { useTheme } from '../hooks/useTheme';
import { useAuth } from '../context/AuthContext';
import UnifiedInterviewModal, { UnifiedInterviewConfig } from './UnifiedInterviewModal';

interface NavItem {
  id: string;
  path: string;
  label: string;
  icon: React.ComponentType<{ className?: string }>;
  description?: string;
}

interface NavGroup {
  id: string;
  title: string;
  items: NavItem[];
}

export default function Layout() {
  const location = useLocation();
  const currentPath = location.pathname;
  const { theme, toggleTheme } = useTheme();
  const { user, isAdmin, logout } = useAuth();
  const navigate = useNavigate();
  const [interviewModalPreset, setInterviewModalPreset] = useState<{
    defaultMode: 'text' | 'voice';
    defaultResumeId?: number;
    title: string;
    subtitle: string;
    startButtonText: string;
  } | null>(null);

  const openInterviewModalWithResume = (resumeId: number) => {
    setInterviewModalPreset({
      defaultMode: 'text',
      defaultResumeId: resumeId,
      title: '开始模拟面试',
      subtitle: '配置面试参数，开始练习',
      startButtonText: '开始面试',
    });
  };

  const handleInterviewStart = (config: UnifiedInterviewConfig) => {
    setInterviewModalPreset(null);
    if (config.mode === 'text') {
      navigate('/interview', {
        state: {
          resumeId: config.resumeId,
          interviewConfig: {
            skillId: config.skillId,
            difficulty: config.difficulty,
            questionCount: config.questionCount,
          },
        },
      });
      return;
    }

    const params = new URLSearchParams({
      skillId: config.skillId,
      difficulty: config.difficulty,
    });
    navigate(`/voice-interview?${params.toString()}`, {
      state: {
        voiceConfig: {
          skillId: config.skillId,
          difficulty: config.difficulty,
          techEnabled: true,
          projectEnabled: true,
          hrEnabled: true,
          plannedDuration: config.plannedDuration,
          resumeId: config.resumeId,
        },
      },
    });
  };

  const navGroups: NavGroup[] = isAdmin
    ? [
        {
          id: 'management',
          title: '管理',
          items: [
            {
              id: 'admin',
              path: '/admin',
              label: '用户管理',
              icon: UserCog,
              description: '用户账号信息',
            },
            {
              id: 'ai-parameters',
              path: '/admin/ai-parameters',
              label: 'AI参数',
              icon: SlidersHorizontal,
              description: '全局业务参数',
            },
          ],
        },
        {
          id: 'account',
          title: '账号',
          items: [
            {
              id: 'profile',
              path: '/profile',
              label: '个人中心',
              icon: UserRound,
              description: '账号资料与安全',
            },
          ],
        },
      ]
    : [
        {
          id: 'interview',
          title: '面试准备',
          items: [
            { id: 'resumes', path: '/history', label: '简历管理', icon: FileStack, description: '管理简历，AI 分析' },
            { id: 'interview-hub', path: '/interview-hub', label: '模拟面试', icon: Sparkles, description: '文字/语音面试练习' },
            { id: 'interviews', path: '/interviews', label: '面试记录', icon: Users, description: '查看面试历史' },
            { id: 'mistakes', path: '/mistakes', label: '错题本', icon: BookOpenCheck, description: '复盘低分问题' },
            { id: 'interview-schedule', path: '/interview-schedule', label: '面试日程', icon: Calendar, description: '管理面试安排' },
          ],
        },
        {
          id: 'knowledge',
          title: '知识库',
          items: [
            { id: 'kb-manage', path: '/knowledgebase', label: '知识库管理', icon: Database, description: '管理知识文档' },
            { id: 'chat', path: '/knowledgebase/chat', label: '问答助手', icon: MessageSquare, description: '基于知识库问答' },
          ],
        },
        {
          id: 'system',
          title: '系统',
          items: [
            { id: 'profile', path: '/profile', label: '个人中心', icon: UserRound, description: '账号资料与成长趋势' },
            {
              id: 'settings',
              path: '/settings',
              label: 'AI设置',
              icon: Settings,
              description: '管理模型和语音服务',
            },
          ],
        },
      ];

  const isActive = (path: string) => {
    if (path === '/history') {
      return currentPath === '/history'
        || currentPath === '/'
        || currentPath.startsWith('/history/')
        || currentPath === '/upload';
    }
    if (path === '/interview-hub') {
      return currentPath === '/interview-hub'
        || currentPath === '/interview'
        || currentPath.startsWith('/interview/')
        || currentPath.startsWith('/voice-interview');
    }
    if (path === '/knowledgebase') {
      return currentPath === '/knowledgebase' || currentPath === '/knowledgebase/upload';
    }
    if (path === '/admin') {
      return currentPath === '/admin' || currentPath === '/admin/users';
    }
    return currentPath === path || currentPath.startsWith(`${path}/`);
  };

  return (
    <div className="flex min-h-screen bg-gradient-to-br from-slate-50 to-indigo-50 dark:from-slate-900 dark:to-slate-800">
      <aside className="fixed left-0 top-0 z-50 flex h-screen w-64 flex-col border-r border-slate-100 bg-white dark:border-slate-700 dark:bg-slate-900">
        <div className="flex items-center justify-between border-b border-slate-100 p-6 dark:border-slate-700">
          <Link to={isAdmin ? '/admin' : '/history'} className="flex items-center gap-3">
            <div className="flex h-10 w-10 items-center justify-center rounded-xl bg-gradient-to-br from-primary-500 to-primary-600 text-white shadow-lg shadow-primary-500/30">
              <Sparkles className="h-5 w-5" />
            </div>
            <div>
              <span className="block text-lg font-bold tracking-tight text-slate-800 dark:text-white">AI Interview</span>
              <span className="text-xs text-slate-400 dark:text-slate-500">智能面试助手</span>
            </div>
          </Link>
        </div>

        <div className="px-4 pb-2">
          <button
            onClick={toggleTheme}
            className="flex w-full items-center justify-center gap-2 rounded-lg bg-slate-100 px-3 py-2 text-slate-600 transition-colors hover:bg-slate-200 dark:bg-slate-800 dark:text-slate-300 dark:hover:bg-slate-700"
          >
            {theme === 'dark' ? (
              <>
                <Sun className="h-4 w-4" />
                <span className="text-sm font-medium">浅色模式</span>
              </>
            ) : (
              <>
                <Moon className="h-4 w-4" />
                <span className="text-sm font-medium">深色模式</span>
              </>
            )}
          </button>
        </div>

        <nav className="flex-1 overflow-y-auto p-4">
          <div className="space-y-6">
            {navGroups.map((group) => (
              <div key={group.id}>
                <div className="mb-2 px-3">
                  <span className="text-xs font-semibold uppercase tracking-wider text-slate-400 dark:text-slate-500">
                    {group.title}
                  </span>
                </div>
                <div className="space-y-1">
                  {group.items.map((item) => {
                    const active = isActive(item.path);
                    return (
                      <Link
                        key={item.id}
                        to={item.path}
                        className={`group relative flex items-center gap-3 rounded-xl px-3 py-2.5 transition-all duration-200 ${
                          active
                            ? 'bg-primary-50 text-primary-600 dark:bg-primary-900/30 dark:text-primary-400'
                            : 'text-slate-600 hover:bg-slate-50 hover:text-slate-900 dark:text-slate-400 dark:hover:bg-slate-800 dark:hover:text-white'
                        }`}
                      >
                        <div
                          className={`flex h-9 w-9 items-center justify-center rounded-lg transition-colors ${
                            active
                              ? 'bg-primary-100 text-primary-600 dark:bg-primary-900/50 dark:text-primary-400'
                              : 'bg-slate-100 text-slate-500 group-hover:bg-slate-200 group-hover:text-slate-700 dark:bg-slate-800 dark:text-slate-400 dark:group-hover:bg-slate-700 dark:group-hover:text-white'
                          }`}
                        >
                          <item.icon className="h-5 w-5" />
                        </div>
                        <div className="min-w-0 flex-1">
                          <span className={`block text-sm ${active ? 'font-semibold' : 'font-medium'}`}>
                            {item.label}
                          </span>
                          {item.description && (
                            <span className="block truncate text-xs text-slate-400 dark:text-slate-500">
                              {item.description}
                            </span>
                          )}
                        </div>
                        {active && <ChevronRight className="h-4 w-4 text-primary-400" />}
                      </Link>
                    );
                  })}
                </div>
              </div>
            ))}
          </div>
        </nav>

        <div className="border-t border-slate-100 p-4 dark:border-slate-700">
          <div className="flex items-center gap-3 rounded-xl bg-gradient-to-r from-primary-50 to-indigo-50 px-3 py-3 dark:from-primary-900/30 dark:to-slate-800">
            <div className="flex h-9 w-9 items-center justify-center rounded-lg bg-white font-semibold text-primary-600 dark:bg-slate-900">
              {user?.displayName?.slice(0, 1).toUpperCase() || 'U'}
            </div>
            <button
              type="button"
              onClick={() => navigate('/profile')}
              className="min-w-0 flex-1 text-left"
              title="进入个人中心"
            >
              <p className="truncate text-sm font-medium text-slate-800 dark:text-white">{user?.displayName}</p>
              <p className="text-xs text-slate-400 dark:text-slate-500">{isAdmin ? '管理员' : '普通用户'}</p>
            </button>
            <button
              onClick={() => {
                logout();
                navigate('/login', { replace: true });
              }}
              className="flex h-9 w-9 items-center justify-center rounded-lg text-slate-400 transition-colors hover:bg-white hover:text-slate-700 dark:hover:bg-slate-900 dark:hover:text-slate-100"
              title="退出登录"
            >
              <LogOut className="h-4 w-4" />
            </button>
          </div>
        </div>
      </aside>

      <main className="ml-64 min-h-screen flex-1 overflow-y-auto p-10">
        <motion.div
          key={currentPath}
          initial={{ opacity: 0, y: 20 }}
          animate={{ opacity: 1, y: 0 }}
          exit={{ opacity: 0, y: -20 }}
          transition={{ duration: 0.3 }}
        >
          <Outlet context={{ openInterviewModalWithResume }} />
        </motion.div>
      </main>

      <UnifiedInterviewModal
        isOpen={interviewModalPreset !== null}
        onClose={() => setInterviewModalPreset(null)}
        onStart={handleInterviewStart}
        defaultMode={interviewModalPreset?.defaultMode || 'text'}
        defaultResumeId={interviewModalPreset?.defaultResumeId}
        hideModeSwitch={interviewModalPreset?.defaultResumeId == null}
        title={interviewModalPreset?.title || '开始模拟面试'}
        subtitle={interviewModalPreset?.subtitle || '选择面试模式和主题，快速开始'}
        startButtonText={interviewModalPreset?.startButtonText || '开始面试'}
      />
    </div>
  );
}
