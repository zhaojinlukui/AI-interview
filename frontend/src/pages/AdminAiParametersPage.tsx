import { useEffect, useState } from 'react';
import { BarChart3, Loader2, Save, SlidersHorizontal } from 'lucide-react';
import { adminApi } from '../api/admin';
import type { SystemAiParameters } from '../types/admin';

const DEFAULT_PARAMETERS: SystemAiParameters = {
  resumeWeights: {
    projectWeight: 40,
    skillMatchWeight: 20,
    contentWeight: 15,
    structureWeight: 15,
    expressionWeight: 10,
  },
  interview: {
    resumeQuestionRatio: 60,
    directionQuestionRatio: 40,
    questionTemperature: 0.2,
    followUpTemperature: 0.2,
    scoringTemperature: 0.2,
    commentTemperature: 0.2,
  },
  ragSearch: {
    topkShort: 20,
    topkMedium: 12,
    topkLong: 8,
    minScoreShort: 0.18,
    minScoreMedium: 0.28,
    minScoreLong: 0.28,
  },
};

export default function AdminAiParametersPage() {
  const [parameters, setParameters] = useState<SystemAiParameters>(DEFAULT_PARAMETERS);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [message, setMessage] = useState('');

  useEffect(() => {
    const load = async () => {
      setLoading(true);
      try {
        setParameters(await adminApi.getAiParameters());
      } finally {
        setLoading(false);
      }
    };
    load();
  }, []);

  const save = async () => {
    setSaving(true);
    setMessage('');
    try {
      await adminApi.updateAiParameters(parameters);
      setMessage('AI 参数已保存，将影响后续新的分析、面试与问答');
    } catch (err) {
      setMessage(err instanceof Error ? err.message : '保存失败');
    } finally {
      setSaving(false);
    }
  };

  if (loading) {
    return <LoadingCard />;
  }

  const resumeTotal = Object.values(parameters.resumeWeights).reduce((sum, value) => sum + value, 0);
  const ratioTotal =
    parameters.interview.resumeQuestionRatio + parameters.interview.directionQuestionRatio;

  return (
    <div className="space-y-6">
      <header className="flex flex-wrap items-center justify-between gap-4">
        <div>
          <h1 className="flex items-center gap-3 text-2xl font-bold text-slate-900 dark:text-white">
            <SlidersHorizontal className="h-7 w-7 text-primary-500" />
            AI参数
          </h1>
          <p className="mt-1 text-sm text-slate-500 dark:text-slate-400">
            全局业务参数设置，不包含模型接入、API Key 或连通性测试
          </p>
        </div>
        <button
          onClick={save}
          disabled={saving}
          className="btn-primary flex h-10 items-center gap-2 rounded-xl px-4 disabled:opacity-60"
        >
          <Save className="h-4 w-4" />
          {saving ? '保存中...' : '保存参数'}
        </button>
      </header>

      <div className="dark-card space-y-6 p-6">
        <ParameterSection
          title={`简历五维权重 · 合计 ${resumeTotal}`}
          warning={resumeTotal !== 100}
        >
          <NumberField
            label="项目经验"
            value={parameters.resumeWeights.projectWeight}
            onChange={(value) =>
              setParameters({
                ...parameters,
                resumeWeights: { ...parameters.resumeWeights, projectWeight: value },
              })
            }
          />
          <NumberField
            label="技能匹配"
            value={parameters.resumeWeights.skillMatchWeight}
            onChange={(value) =>
              setParameters({
                ...parameters,
                resumeWeights: { ...parameters.resumeWeights, skillMatchWeight: value },
              })
            }
          />
          <NumberField
            label="内容完整"
            value={parameters.resumeWeights.contentWeight}
            onChange={(value) =>
              setParameters({
                ...parameters,
                resumeWeights: { ...parameters.resumeWeights, contentWeight: value },
              })
            }
          />
          <NumberField
            label="结构清晰"
            value={parameters.resumeWeights.structureWeight}
            onChange={(value) =>
              setParameters({
                ...parameters,
                resumeWeights: { ...parameters.resumeWeights, structureWeight: value },
              })
            }
          />
          <NumberField
            label="表达专业"
            value={parameters.resumeWeights.expressionWeight}
            onChange={(value) =>
              setParameters({
                ...parameters,
                resumeWeights: { ...parameters.resumeWeights, expressionWeight: value },
              })
            }
          />
        </ParameterSection>

        <ParameterSection title={`面试参数 · 问题类型占比合计 ${ratioTotal}`} warning={ratioTotal !== 100}>
          <NumberField
            label="简历题占比"
            value={parameters.interview.resumeQuestionRatio}
            onChange={(value) =>
              setParameters({
                ...parameters,
                interview: { ...parameters.interview, resumeQuestionRatio: value },
              })
            }
          />
          <NumberField
            label="方向题占比"
            value={parameters.interview.directionQuestionRatio}
            onChange={(value) =>
              setParameters({
                ...parameters,
                interview: { ...parameters.interview, directionQuestionRatio: value },
              })
            }
          />
        </ParameterSection>

        <ParameterSection title="面试参数 · 温度">
          <NumberField
            label="出题温度"
            step={0.1}
            value={parameters.interview.questionTemperature}
            onChange={(value) =>
              setParameters({
                ...parameters,
                interview: { ...parameters.interview, questionTemperature: value },
              })
            }
          />
          <NumberField
            label="追问温度"
            step={0.1}
            value={parameters.interview.followUpTemperature}
            onChange={(value) =>
              setParameters({
                ...parameters,
                interview: { ...parameters.interview, followUpTemperature: value },
              })
            }
          />
          <NumberField
            label="评分温度"
            step={0.1}
            value={parameters.interview.scoringTemperature}
            onChange={(value) =>
              setParameters({
                ...parameters,
                interview: { ...parameters.interview, scoringTemperature: value },
              })
            }
          />
          <NumberField
            label="评语温度"
            step={0.1}
            value={parameters.interview.commentTemperature}
            onChange={(value) =>
              setParameters({
                ...parameters,
                interview: { ...parameters.interview, commentTemperature: value },
              })
            }
          />
        </ParameterSection>

        <ParameterSection title="问答助手检索参数">
          <NumberField
            label="短问题 topK"
            value={parameters.ragSearch.topkShort}
            onChange={(value) =>
              setParameters({
                ...parameters,
                ragSearch: { ...parameters.ragSearch, topkShort: value },
              })
            }
          />
          <NumberField
            label="中问题 topK"
            value={parameters.ragSearch.topkMedium}
            onChange={(value) =>
              setParameters({
                ...parameters,
                ragSearch: { ...parameters.ragSearch, topkMedium: value },
              })
            }
          />
          <NumberField
            label="长问题 topK"
            value={parameters.ragSearch.topkLong}
            onChange={(value) =>
              setParameters({
                ...parameters,
                ragSearch: { ...parameters.ragSearch, topkLong: value },
              })
            }
          />
          <NumberField
            label="短问题最小相似度"
            step={0.01}
            value={parameters.ragSearch.minScoreShort}
            onChange={(value) =>
              setParameters({
                ...parameters,
                ragSearch: { ...parameters.ragSearch, minScoreShort: value },
              })
            }
          />
          <NumberField
            label="中问题最小相似度"
            step={0.01}
            value={parameters.ragSearch.minScoreMedium}
            onChange={(value) =>
              setParameters({
                ...parameters,
                ragSearch: { ...parameters.ragSearch, minScoreMedium: value },
              })
            }
          />
          <NumberField
            label="长问题最小相似度"
            step={0.01}
            value={parameters.ragSearch.minScoreLong}
            onChange={(value) =>
              setParameters({
                ...parameters,
                ragSearch: { ...parameters.ragSearch, minScoreLong: value },
              })
            }
          />
        </ParameterSection>

        {message && <p className="text-sm text-slate-500 dark:text-slate-400">{message}</p>}
      </div>
    </div>
  );
}

function ParameterSection({
  title,
  warning,
  children,
}: {
  title: string;
  warning?: boolean;
  children: React.ReactNode;
}) {
  return (
    <section>
      <h2
        className={`mb-3 flex items-center gap-2 text-sm font-semibold ${
          warning ? 'text-amber-600 dark:text-amber-300' : 'text-slate-900 dark:text-white'
        }`}
      >
        <BarChart3 className="h-4 w-4" />
        {title}
      </h2>
      <div className="grid gap-3 md:grid-cols-2 xl:grid-cols-3">{children}</div>
    </section>
  );
}

function NumberField({
  label,
  value,
  step = 1,
  onChange,
}: {
  label: string;
  value: number;
  step?: number;
  onChange: (value: number) => void;
}) {
  return (
    <label className="block">
      <span className="mb-1.5 block text-xs font-medium text-slate-500 dark:text-slate-400">
        {label}
      </span>
      <input
        type="number"
        step={step}
        value={value}
        onChange={(event) => onChange(Number(event.target.value))}
        className="dark-input h-10 w-full rounded-xl px-3"
      />
    </label>
  );
}

function LoadingCard() {
  return (
    <div className="dark-card flex h-56 items-center justify-center">
      <Loader2 className="h-6 w-6 animate-spin text-primary-500" />
    </div>
  );
}
