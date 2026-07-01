import {useEffect, useMemo, useState} from 'react';
import {useNavigate} from 'react-router-dom';
import {AnimatePresence, motion} from 'framer-motion';
import {
  AlertCircle,
  BookOpenCheck,
  ChevronDown,
  ChevronRight,
  FileText,
  Loader2,
  Mic,
  RefreshCw,
  RotateCcw,
  Search,
  Target,
} from 'lucide-react';
import {mistakeApi, MistakeSession, MistakeSourceType} from '../api/mistake';
import {formatDate} from '../utils/date';
import {getScoreProgressColor, getScoreTextColor} from '../utils/score';

type SourceFilter = 'all' | MistakeSourceType;

function SourceBadge({sourceType}: { sourceType: MistakeSourceType }) {
  if (sourceType === 'VOICE') {
    return (
      <span className="inline-flex items-center gap-1.5 px-2.5 py-1 bg-purple-100 dark:bg-purple-900/30 text-purple-700 dark:text-purple-300 rounded-full text-xs font-medium">
        <Mic className="w-3.5 h-3.5" />
        语音面试
      </span>
    );
  }

  return (
    <span className="inline-flex items-center gap-1.5 px-2.5 py-1 bg-blue-100 dark:bg-blue-900/30 text-blue-700 dark:text-blue-300 rounded-full text-xs font-medium">
      <FileText className="w-3.5 h-3.5" />
      文字面试
    </span>
  );
}

export default function MistakePage() {
  const navigate = useNavigate();
  const [items, setItems] = useState<MistakeSession[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [searchTerm, setSearchTerm] = useState('');
  const [sourceFilter, setSourceFilter] = useState<SourceFilter>('all');
  const [expanded, setExpanded] = useState<string | null>(null);
  const [creatingPractice, setCreatingPractice] = useState<string | null>(null);

  const loadMistakes = async () => {
    setLoading(true);
    setError('');
    try {
      const data = await mistakeApi.listMistakes();
      setItems(data);
    } catch (err) {
      console.error('加载错题失败', err);
      setError(err instanceof Error ? err.message : '加载错题失败，请稍后重试');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    loadMistakes();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const stats = useMemo(() => {
    const mistakeCount = items.reduce((sum, item) => sum + item.mistakeCount, 0);
    const textCount = items.filter(item => item.sourceType === 'TEXT').length;
    const voiceCount = items.filter(item => item.sourceType === 'VOICE').length;
    return {mistakeCount, textCount, voiceCount};
  }, [items]);

  const filtered = items.filter(item => {
    if (sourceFilter !== 'all' && item.sourceType !== sourceFilter) return false;
    if (searchTerm && !item.title.toLowerCase().includes(searchTerm.toLowerCase())) return false;
    return true;
  });

  const handlePractice = async (item: MistakeSession) => {
    const key = `${item.sourceType}-${item.sourceSessionId}`;
    setCreatingPractice(key);
    try {
      const response = await mistakeApi.createPractice(item.sourceType, item.sourceSessionId);
      navigate('/interview', {state: {sessionIdToResume: response.sessionId}});
    } catch (err) {
      alert(err instanceof Error ? err.message : '创建重练场次失败，请稍后重试');
    } finally {
      setCreatingPractice(null);
    }
  };

  return (
    <motion.div className="w-full" initial={{opacity: 0}} animate={{opacity: 1}}>
      <div className="flex justify-between items-start mb-8 flex-wrap gap-6">
        <div>
          <motion.h1
            className="text-2xl font-bold text-slate-800 dark:text-white flex items-center gap-3"
            initial={{opacity: 0, x: -20}}
            animate={{opacity: 1, x: 0}}
          >
            <BookOpenCheck className="w-7 h-7 text-primary-500" />
            错题本
          </motion.h1>
          <motion.p
            className="text-slate-500 dark:text-slate-400 mt-1"
            initial={{opacity: 0}}
            animate={{opacity: 1}}
            transition={{delay: 0.1}}
          >
            汇总低于 60 分的问题，按面试场次复盘并重新练习
          </motion.p>
        </div>

        <div className="flex items-center gap-3">
          <motion.div
            className="flex items-center gap-3 bg-white dark:bg-slate-800 border border-slate-200 dark:border-slate-600 rounded-xl px-4 py-2.5 min-w-[280px] focus-within:border-primary-500 focus-within:ring-2 focus-within:ring-primary-100 dark:focus-within:ring-primary-900/30 transition-all"
            initial={{opacity: 0, x: 20}}
            animate={{opacity: 1, x: 0}}
          >
            <Search className="w-5 h-5 text-slate-400" />
            <input
              type="text"
              placeholder="搜索面试名称..."
              value={searchTerm}
              onChange={(e) => setSearchTerm(e.target.value)}
              className="flex-1 outline-none text-slate-700 dark:text-slate-200 placeholder:text-slate-400 bg-transparent"
            />
          </motion.div>
          <button
            onClick={loadMistakes}
            className="p-3 bg-white dark:bg-slate-800 border border-slate-200 dark:border-slate-600 text-slate-500 dark:text-slate-300 rounded-xl hover:bg-slate-50 dark:hover:bg-slate-700 transition-colors"
            title="刷新"
          >
            <RefreshCw className="w-5 h-5" />
          </button>
        </div>
      </div>

      <div className="grid grid-cols-1 md:grid-cols-3 gap-6 mb-8">
        <StatCard icon={Target} label="错题总数" value={stats.mistakeCount} color="bg-rose-500" />
        <StatCard icon={FileText} label="文字来源" value={stats.textCount} color="bg-blue-500" />
        <StatCard icon={Mic} label="语音来源" value={stats.voiceCount} color="bg-purple-500" />
      </div>

      <div className="flex items-center gap-2 mb-6">
        {([
          {key: 'all', label: '全部'},
          {key: 'TEXT', label: '文字面试'},
          {key: 'VOICE', label: '语音面试'},
        ] as const).map(tab => (
          <button
            key={tab.key}
            onClick={() => setSourceFilter(tab.key)}
            className={`px-4 py-2 rounded-lg text-sm font-medium transition-colors ${
              sourceFilter === tab.key
                ? 'bg-primary-500 text-white'
                : 'bg-white dark:bg-slate-800 text-slate-600 dark:text-slate-300 hover:bg-slate-100 dark:hover:bg-slate-700 border border-slate-200 dark:border-slate-600'
            }`}
          >
            {tab.label}
          </button>
        ))}
      </div>

      {loading && (
        <div className="flex items-center justify-center py-20">
          <Loader2 className="w-8 h-8 text-primary-500 animate-spin" />
        </div>
      )}

      {!loading && error && (
        <div className="bg-white dark:bg-slate-800 border border-red-100 dark:border-red-900/40 rounded-xl p-8 text-center">
          <AlertCircle className="w-12 h-12 text-red-400 mx-auto mb-4" />
          <p className="text-slate-700 dark:text-slate-200 font-medium mb-2">错题加载失败</p>
          <p className="text-slate-500 dark:text-slate-400 text-sm mb-5">{error}</p>
          <button
            onClick={loadMistakes}
            className="px-5 py-2 bg-primary-500 text-white rounded-lg hover:bg-primary-600"
          >
            重试
          </button>
        </div>
      )}

      {!loading && !error && filtered.length === 0 && (
        <motion.div
          className="text-center py-20 bg-white dark:bg-slate-800 rounded-2xl shadow-sm border border-slate-100 dark:border-slate-700"
          initial={{opacity: 0, scale: 0.95}}
          animate={{opacity: 1, scale: 1}}
        >
          <BookOpenCheck className="w-16 h-16 text-slate-300 dark:text-slate-600 mx-auto mb-4" />
          <h3 className="text-xl font-semibold text-slate-700 dark:text-slate-300 mb-2">暂无错题</h3>
          <p className="text-slate-500 dark:text-slate-400">完成评估后，低于 60 分的问题会自动出现在这里</p>
        </motion.div>
      )}

      {!loading && !error && filtered.length > 0 && (
        <div className="space-y-4">
          {filtered.map((item, index) => {
            const key = `${item.sourceType}-${item.sourceSessionId}`;
            const isExpanded = expanded === key;
            const isCreating = creatingPractice === key;

            return (
              <motion.div
                key={key}
                initial={{opacity: 0, y: 16}}
                animate={{opacity: 1, y: 0}}
                transition={{delay: index * 0.04}}
                className="bg-white dark:bg-slate-800 border border-slate-100 dark:border-slate-700 rounded-xl shadow-sm overflow-hidden"
              >
                <button
                  onClick={() => setExpanded(isExpanded ? null : key)}
                  className="w-full px-6 py-5 flex items-center gap-5 text-left hover:bg-slate-50 dark:hover:bg-slate-700/50 transition-colors"
                >
                  <div className="w-11 h-11 rounded-xl bg-rose-50 dark:bg-rose-900/20 text-rose-500 flex items-center justify-center flex-shrink-0">
                    <Target className="w-5 h-5" />
                  </div>
                  <div className="flex-1 min-w-0">
                    <div className="flex items-center gap-3 flex-wrap mb-2">
                      <SourceBadge sourceType={item.sourceType} />
                      <span className="text-xs text-slate-400 dark:text-slate-500">
                        {formatDate(item.createdAt)}
                      </span>
                    </div>
                    <h2 className="font-semibold text-slate-800 dark:text-white truncate">{item.title}</h2>
                    <p className="text-xs text-slate-400 dark:text-slate-500 mt-1">
                      来源场次 #{item.sourceSessionId.slice(-8)}
                    </p>
                  </div>
                  <div className="hidden md:flex items-center gap-5">
                    <div className="text-right">
                      <p className="text-xs text-slate-400 dark:text-slate-500">错题数</p>
                      <p className="text-lg font-bold text-rose-500">{item.mistakeCount}</p>
                    </div>
                    <div className="text-right min-w-[88px]">
                      <p className="text-xs text-slate-400 dark:text-slate-500">原总分</p>
                      {item.overallScore != null ? (
                        <p className={`text-lg font-bold ${getScoreTextColor(item.overallScore, [80, 60])}`}>
                          {item.overallScore}
                        </p>
                      ) : (
                        <p className="text-lg font-bold text-slate-400">-</p>
                      )}
                    </div>
                  </div>
                  {isExpanded ? (
                    <ChevronDown className="w-5 h-5 text-slate-400" />
                  ) : (
                    <ChevronRight className="w-5 h-5 text-slate-400" />
                  )}
                </button>

                <AnimatePresence>
                  {isExpanded && (
                    <motion.div
                      initial={{height: 0, opacity: 0}}
                      animate={{height: 'auto', opacity: 1}}
                      exit={{height: 0, opacity: 0}}
                      className="overflow-hidden"
                    >
                      <div className="px-6 pb-6 border-t border-slate-100 dark:border-slate-700">
                        <div className="flex justify-end py-4">
                          <button
                            onClick={() => handlePractice(item)}
                            disabled={isCreating}
                            className="inline-flex items-center gap-2 px-4 py-2 bg-primary-500 text-white rounded-lg hover:bg-primary-600 disabled:opacity-60 transition-colors text-sm font-medium"
                          >
                            {isCreating ? (
                              <Loader2 className="w-4 h-4 animate-spin" />
                            ) : (
                              <RotateCcw className="w-4 h-4" />
                            )}
                            重新练习
                          </button>
                        </div>

                        <div className="space-y-4">
                          {item.mistakes.map((mistake, mistakeIndex) => (
                            <div
                              key={`${key}-${mistake.questionIndex}`}
                              className="rounded-xl border border-slate-100 dark:border-slate-700 bg-slate-50/70 dark:bg-slate-900/40 p-5"
                            >
                              <div className="flex items-start justify-between gap-4 mb-3">
                                <div className="flex items-start gap-3">
                                  <span className="w-7 h-7 rounded-lg bg-white dark:bg-slate-800 border border-slate-200 dark:border-slate-700 text-slate-500 dark:text-slate-300 flex items-center justify-center text-sm font-semibold">
                                    {mistakeIndex + 1}
                                  </span>
                                  <div>
                                    <p className="font-medium text-slate-800 dark:text-white leading-relaxed">
                                      {mistake.question}
                                    </p>
                                    <p className="text-xs text-slate-400 dark:text-slate-500 mt-1">
                                      {mistake.category || '综合问题'}
                                    </p>
                                  </div>
                                </div>
                                <div className="flex-shrink-0 text-right">
                                  <p className="text-xs text-slate-400 dark:text-slate-500 mb-1">得分</p>
                                  <span
                                    className={`text-lg font-bold ${
                                      mistake.score != null
                                        ? getScoreTextColor(mistake.score, [80, 60])
                                        : 'text-slate-400'
                                    }`}
                                  >
                                    {mistake.score ?? '-'}
                                  </span>
                                </div>
                              </div>

                              <div className="h-1.5 bg-slate-200 dark:bg-slate-700 rounded-full overflow-hidden mb-4">
                                <div
                                  className={`h-full ${getScoreProgressColor(mistake.score ?? 0)} rounded-full`}
                                  style={{width: `${Math.max(0, Math.min(mistake.score ?? 0, 100))}%`}}
                                />
                              </div>

                              <div className="grid grid-cols-1 lg:grid-cols-2 gap-4 text-sm">
                                <InfoBlock title="我的回答" content={mistake.userAnswer || '未回答'} />
                                <InfoBlock title="评估反馈" content={mistake.feedback || '暂无反馈'} />
                                {mistake.referenceAnswer && (
                                  <InfoBlock title="参考答案" content={mistake.referenceAnswer} />
                                )}
                                {mistake.keyPoints && mistake.keyPoints.length > 0 && (
                                  <div>
                                    <p className="text-xs font-semibold text-slate-500 dark:text-slate-400 mb-2">关键点</p>
                                    <div className="flex flex-wrap gap-2">
                                      {mistake.keyPoints.map(point => (
                                        <span
                                          key={point}
                                          className="px-2.5 py-1 bg-white dark:bg-slate-800 border border-slate-200 dark:border-slate-700 rounded-lg text-xs text-slate-600 dark:text-slate-300"
                                        >
                                          {point}
                                        </span>
                                      ))}
                                    </div>
                                  </div>
                                )}
                              </div>
                            </div>
                          ))}
                        </div>
                      </div>
                    </motion.div>
                  )}
                </AnimatePresence>
              </motion.div>
            );
          })}
        </div>
      )}
    </motion.div>
  );
}

function StatCard({
  icon: Icon,
  label,
  value,
  color,
}: {
  icon: React.ComponentType<{ className?: string }>;
  label: string;
  value: number | string;
  color: string;
}) {
  return (
    <motion.div
      initial={{opacity: 0, y: 20}}
      animate={{opacity: 1, y: 0}}
      className="bg-white dark:bg-slate-800 rounded-xl p-6 shadow-sm border border-slate-100 dark:border-slate-700"
    >
      <div className="flex items-center gap-4">
        <div className={`p-3 rounded-lg ${color}`}>
          <Icon className="w-6 h-6 text-white" />
        </div>
        <div>
          <p className="text-sm text-slate-500 dark:text-slate-400">{label}</p>
          <p className="text-2xl font-bold text-slate-800 dark:text-white">{value}</p>
        </div>
      </div>
    </motion.div>
  );
}

function InfoBlock({title, content}: { title: string; content: string }) {
  return (
    <div>
      <p className="text-xs font-semibold text-slate-500 dark:text-slate-400 mb-2">{title}</p>
      <p className="text-slate-600 dark:text-slate-300 leading-relaxed whitespace-pre-wrap">{content}</p>
    </div>
  );
}
