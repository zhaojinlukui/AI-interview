import { useEffect, useMemo, useState } from 'react';
import { Bot, Cloud, Cpu, KeyRound, Loader2, Mic, Volume2, Wifi } from 'lucide-react';
import { aiSettingsApi } from '../api/aiSettings';
import type {
  AsrConfig,
  AsrConfigRequest,
  ModelSettings,
  ModelSettingsRequest,
  SettingsTestResult,
  TtsConfig,
  TtsConfigRequest,
} from '../types/aiSettings';

function SectionCard({
  title,
  description,
  children,
}: {
  title: string;
  description: string;
  children: React.ReactNode;
}) {
  return (
    <section className="rounded-2xl border border-slate-200 bg-white p-6 shadow-sm dark:border-slate-700 dark:bg-slate-800">
      <div className="mb-5">
        <h2 className="text-lg font-semibold text-slate-900 dark:text-white">{title}</h2>
        <p className="mt-1 text-sm text-slate-500 dark:text-slate-400">{description}</p>
      </div>
      {children}
    </section>
  );
}

function Field({
  label,
  children,
}: {
  label: string;
  children: React.ReactNode;
}) {
  return (
    <label className="block">
      <span className="mb-2 block text-sm font-medium text-slate-700 dark:text-slate-200">{label}</span>
      {children}
    </label>
  );
}

function inputClass() {
  return `w-full rounded-xl border border-slate-200 bg-white px-4 py-3 text-sm text-slate-900
    outline-none transition focus:border-primary-500 focus:ring-2 focus:ring-primary-500/20
    dark:border-slate-700 dark:bg-slate-900 dark:text-white`;
}

function toOptionalString(value: string) {
  return value.trim() ? value : undefined;
}

function toOptionalNumber(value: string) {
  return value.trim() ? Number(value) : undefined;
}

type ModelSlot = 'cloud' | 'local';

type ModelSlotForm = Pick<
  ModelSettingsRequest,
  'baseUrl' | 'apiKey' | 'chatModel' | 'embeddingModel' | 'embeddingDimensions'
>;

const DEFAULT_CLOUD_TEXT_MODEL: ModelSlotForm = {
  baseUrl: 'https://dashscope.aliyuncs.com/compatible-mode/v1',
  chatModel: 'qwen-turbo',
  embeddingModel: 'text-embedding-v4',
  embeddingDimensions: 1024,
};

const DEFAULT_LOCAL_TEXT_MODEL: ModelSlotForm = {
  baseUrl: 'http://localhost:11434/v1',
  apiKey: 'ollama',
  chatModel: 'qwen2.5:1.5b',
};

function inferModelSlot(baseUrl: string): ModelSlot {
  return /localhost|127\.0\.0\.1|0\.0\.0\.0|11434/i.test(baseUrl) ? 'local' : 'cloud';
}

function toModelSlotRequest(
  form: ModelSlotForm,
  slot: ModelSlot,
  cloudEmbeddingForm?: ModelSlotForm
): ModelSettingsRequest {
  return {
    baseUrl: form.baseUrl,
    chatModel: form.chatModel,
    embeddingModel: slot === 'cloud' ? form.embeddingModel : undefined,
    embeddingDimensions: slot === 'cloud' ? form.embeddingDimensions : undefined,
    cloudEmbeddingBaseUrl: slot === 'local' ? cloudEmbeddingForm?.baseUrl : undefined,
    cloudEmbeddingApiKey: slot === 'local'
      ? toOptionalString(cloudEmbeddingForm?.apiKey ?? '')
      : undefined,
    cloudEmbeddingModel: slot === 'local' ? cloudEmbeddingForm?.embeddingModel : undefined,
    cloudEmbeddingDimensions: slot === 'local'
      ? cloudEmbeddingForm?.embeddingDimensions
      : undefined,
    apiKey: toOptionalString(form.apiKey ?? ''),
  };
}

export default function SettingsPage() {
  const [loading, setLoading] = useState(true);
  const [savingModel, setSavingModel] = useState(false);
  const [savingAsr, setSavingAsr] = useState(false);
  const [savingTts, setSavingTts] = useState(false);
  const [testingModel, setTestingModel] = useState(false);
  const [testingAsr, setTestingAsr] = useState(false);

  const [modelSettings, setModelSettings] = useState<ModelSettings | null>(null);
  const [asrConfig, setAsrConfig] = useState<AsrConfig | null>(null);
  const [ttsConfig, setTtsConfig] = useState<TtsConfig | null>(null);

  const [activeModelSlot, setActiveModelSlot] = useState<ModelSlot>('cloud');
  const [cloudModelForm, setCloudModelForm] = useState<ModelSlotForm>(DEFAULT_CLOUD_TEXT_MODEL);
  const [localModelForm, setLocalModelForm] = useState<ModelSlotForm>(DEFAULT_LOCAL_TEXT_MODEL);
  const [asrForm, setAsrForm] = useState<AsrConfigRequest>({});
  const [ttsForm, setTtsForm] = useState<TtsConfigRequest>({});

  const [modelTest, setModelTest] = useState<SettingsTestResult | null>(null);
  const [asrTest, setAsrTest] = useState<SettingsTestResult | null>(null);
  const [notice, setNotice] = useState('');
  const [error, setError] = useState('');

  const loadData = async () => {
    setLoading(true);
    setError('');
    try {
      const [modelData, asrData, ttsData] = await Promise.all([
        aiSettingsApi.getModelSettings(),
        aiSettingsApi.getAsrConfig(),
        aiSettingsApi.getTtsConfig(),
      ]);
      setModelSettings(modelData);
      setAsrConfig(asrData);
      setTtsConfig(ttsData);
      const currentSlot = inferModelSlot(modelData.baseUrl);
      setActiveModelSlot(currentSlot);
      const currentLocalModel = {
        baseUrl: modelData.baseUrl,
        chatModel: modelData.chatModel,
      };
      const currentCloudModel = {
        baseUrl: modelData.cloudEmbeddingBaseUrl || DEFAULT_CLOUD_TEXT_MODEL.baseUrl,
        chatModel: currentSlot === 'cloud'
          ? modelData.chatModel
          : DEFAULT_CLOUD_TEXT_MODEL.chatModel,
        embeddingModel: modelData.cloudEmbeddingModel
          ?? modelData.embeddingModel
          ?? DEFAULT_CLOUD_TEXT_MODEL.embeddingModel,
        embeddingDimensions: modelData.cloudEmbeddingDimensions
          ?? modelData.embeddingDimensions
          ?? DEFAULT_CLOUD_TEXT_MODEL.embeddingDimensions,
      };
      setCloudModelForm({
        ...DEFAULT_CLOUD_TEXT_MODEL,
        ...currentCloudModel,
      });
      setLocalModelForm({
        ...DEFAULT_LOCAL_TEXT_MODEL,
        ...(currentSlot === 'local' ? currentLocalModel : {}),
      });
      setAsrForm({
        url: asrData.url,
        model: asrData.model,
        language: asrData.language,
        format: asrData.format,
        sampleRate: asrData.sampleRate,
        enableTurnDetection: asrData.enableTurnDetection,
        turnDetectionType: asrData.turnDetectionType,
        turnDetectionThreshold: asrData.turnDetectionThreshold,
        turnDetectionSilenceDurationMs: asrData.turnDetectionSilenceDurationMs,
      });
      setTtsForm({
        model: ttsData.model,
        voice: ttsData.voice,
        format: ttsData.format,
        sampleRate: ttsData.sampleRate,
        mode: ttsData.mode,
        languageType: ttsData.languageType,
        speechRate: ttsData.speechRate,
      });
    } catch (err) {
      setError(err instanceof Error ? err.message : '加载设置失败');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    loadData();
  }, []);

  const modelSummary = useMemo(() => {
    if (!modelSettings) {
      return '';
    }
    return modelSettings.chatModel;
  }, [modelSettings]);

  const cloudApiKeyPlaceholder =
    activeModelSlot === 'cloud' && modelSettings?.maskedApiKey
      ? modelSettings.maskedApiKey
      : modelSettings?.maskedCloudEmbeddingApiKey
        ? modelSettings.maskedCloudEmbeddingApiKey
      : '留空则切换时不更新 API Key';

  const hasStoredCloudApiKey = Boolean(
    modelSettings?.maskedCloudEmbeddingApiKey || (
      activeModelSlot === 'cloud' && modelSettings?.maskedApiKey
    )
  );

  const handleApplyModelSlot = async (slot: ModelSlot) => {
    setSavingModel(true);
    setNotice('');
    setError('');
    setModelTest(null);
    try {
      const form = slot === 'cloud' ? cloudModelForm : localModelForm;
      if (slot === 'cloud' && !toOptionalString(form.apiKey ?? '') && !hasStoredCloudApiKey) {
        setError('首次使用云端模型需要填写 API Key');
        return;
      }
      await aiSettingsApi.updateModelSettings(toModelSlotRequest(form, slot, cloudModelForm));
      setNotice(slot === 'cloud' ? '云端模型配置已生效' : '本地模型配置已生效');
      await loadData();
    } catch (err) {
      setError(err instanceof Error ? err.message : '切换模型失败');
    } finally {
      setSavingModel(false);
    }
  };

  const handleSaveAsr = async () => {
    setSavingAsr(true);
    setNotice('');
    setError('');
    try {
      await aiSettingsApi.updateAsrConfig(asrForm);
      setNotice('ASR 配置已保存');
      await loadData();
    } catch (err) {
      setError(err instanceof Error ? err.message : '保存 ASR 配置失败');
    } finally {
      setSavingAsr(false);
    }
  };

  const handleSaveTts = async () => {
    setSavingTts(true);
    setNotice('');
    setError('');
    try {
      await aiSettingsApi.updateTtsConfig(ttsForm);
      setNotice('TTS 配置已保存');
      await loadData();
    } catch (err) {
      setError(err instanceof Error ? err.message : '保存 TTS 配置失败');
    } finally {
      setSavingTts(false);
    }
  };

  const handleClearAsrKey = async () => {
    await aiSettingsApi.updateAsrConfig({ apiKey: '' });
    setNotice('ASR 已恢复继承主模型 API Key');
    await loadData();
  };

  const handleClearTtsKey = async () => {
    await aiSettingsApi.updateTtsConfig({ apiKey: '' });
    setNotice('TTS 已恢复继承主模型 API Key');
    await loadData();
  };

  const handleTestModel = async () => {
    setTestingModel(true);
    try {
      setModelTest(await aiSettingsApi.testModelSettings());
    } catch (err) {
      setModelTest({
        success: false,
        message: err instanceof Error ? err.message : '测试失败',
        model: modelSettings?.chatModel ?? '',
      });
    } finally {
      setTestingModel(false);
    }
  };

  const handleTestAsr = async () => {
    setTestingAsr(true);
    try {
      setAsrTest(await aiSettingsApi.testAsr());
    } catch (err) {
      setAsrTest({
        success: false,
        message: err instanceof Error ? err.message : '测试失败',
        model: asrForm.model ?? '',
      });
    } finally {
      setTestingAsr(false);
    }
  };

  if (loading) {
    return (
      <div className="flex min-h-[50vh] items-center justify-center">
        <Loader2 className="h-8 w-8 animate-spin text-primary-500" />
      </div>
    );
  }

  return (
    <div className="mx-auto max-w-7xl space-y-6">
      <div>
        <h1 className="flex items-center gap-3 text-2xl font-bold text-slate-900 dark:text-white">
          <Bot className="h-7 w-7 text-primary-500" />
          AI 设置
        </h1>
        <p className="mt-2 text-sm text-slate-500 dark:text-slate-400">
          本地模型仅用于聊天生成；知识库检索使用系统内置配置。
        </p>
      </div>

      {notice && (
        <div className="rounded-xl border border-emerald-200 bg-emerald-50 px-4 py-3 text-sm text-emerald-700 dark:border-emerald-900/50 dark:bg-emerald-900/20 dark:text-emerald-300">
          {notice}
        </div>
      )}
      {error && (
        <div className="rounded-xl border border-rose-200 bg-rose-50 px-4 py-3 text-sm text-rose-700 dark:border-rose-900/50 dark:bg-rose-900/20 dark:text-rose-300">
          {error}
        </div>
      )}

      <div className="grid gap-6 xl:grid-cols-2">
        <SectionCard
          title="云端模型设置"
          description={activeModelSlot === 'cloud' ? `当前使用：${modelSummary || '未加载'}` : '云端 OpenAI 兼容模型配置'}
        >
          <div className="grid gap-4 2xl:grid-cols-2">
            <Field label="Base URL">
              <input
                className={inputClass()}
                value={cloudModelForm.baseUrl ?? ''}
                onChange={e => setCloudModelForm(prev => ({ ...prev, baseUrl: e.target.value }))}
              />
            </Field>
            <Field label="API Key">
              <input
                className={inputClass()}
                type="password"
                placeholder={cloudApiKeyPlaceholder}
                onChange={e => setCloudModelForm(prev => ({
                  ...prev,
                  apiKey: toOptionalString(e.target.value),
                }))}
              />
            </Field>
            <Field label="聊天模型">
              <input
                className={inputClass()}
                value={cloudModelForm.chatModel ?? ''}
                onChange={e => setCloudModelForm(prev => ({ ...prev, chatModel: e.target.value }))}
              />
            </Field>
            <Field label="向量模型">
              <input
                className={inputClass()}
                value={cloudModelForm.embeddingModel ?? ''}
                onChange={e => setCloudModelForm(prev => ({
                  ...prev,
                  embeddingModel: e.target.value,
                }))}
              />
            </Field>
          </div>
          <div className="mt-5 flex flex-wrap gap-3">
            <button
              onClick={() => handleApplyModelSlot('cloud')}
              disabled={savingModel}
              className="inline-flex items-center gap-2 rounded-xl bg-primary-500 px-4 py-2.5 text-sm font-medium text-white hover:bg-primary-600 disabled:opacity-60"
            >
              {savingModel ? (
                <Loader2 className="h-4 w-4 animate-spin" />
              ) : (
                <Cloud className="h-4 w-4" />
              )}
              {activeModelSlot === 'cloud' ? '更新当前云端模型' : '切换为云端模型'}
            </button>
            <button
              onClick={handleTestModel}
              disabled={testingModel}
              className="inline-flex items-center gap-2 rounded-xl border border-slate-200 px-4 py-2.5 text-sm font-medium text-slate-700 hover:bg-slate-50 disabled:opacity-60 dark:border-slate-700 dark:text-slate-200 dark:hover:bg-slate-700/50"
            >
              {testingModel ? (
                <Loader2 className="h-4 w-4 animate-spin" />
              ) : (
                <Wifi className="h-4 w-4" />
              )}
              测试当前模型
            </button>
          </div>
          {modelTest && (
            <p className={`mt-4 text-sm ${modelTest.success ? 'text-emerald-600 dark:text-emerald-300' : 'text-rose-600 dark:text-rose-300'}`}>
              {modelTest.message} {modelTest.model ? `(${modelTest.model})` : ''}
            </p>
          )}
        </SectionCard>

        <SectionCard
          title="本地模型设置"
          description={activeModelSlot === 'local' ? `当前使用：${modelSummary || '未加载'}` : 'Ollama、vLLM 或 LM Studio 聊天模型配置'}
        >
          <div className="grid gap-4 2xl:grid-cols-2">
            <Field label="Base URL">
              <input
                className={inputClass()}
                value={localModelForm.baseUrl ?? ''}
                onChange={e => setLocalModelForm(prev => ({ ...prev, baseUrl: e.target.value }))}
              />
            </Field>
            <Field label="API Key">
              <input
                className={inputClass()}
                type="password"
                placeholder={localModelForm.apiKey || 'Ollama 可填写 ollama'}
                onChange={e => setLocalModelForm(prev => ({
                  ...prev,
                  apiKey: toOptionalString(e.target.value),
                }))}
              />
            </Field>
            <Field label="聊天模型">
              <input
                className={inputClass()}
                value={localModelForm.chatModel ?? ''}
                onChange={e => setLocalModelForm(prev => ({ ...prev, chatModel: e.target.value }))}
              />
            </Field>
          </div>
          <div className="mt-5 flex flex-wrap gap-3">
            <button
              onClick={() => handleApplyModelSlot('local')}
              disabled={savingModel}
              className="inline-flex items-center gap-2 rounded-xl bg-primary-500 px-4 py-2.5 text-sm font-medium text-white hover:bg-primary-600 disabled:opacity-60"
            >
              {savingModel ? (
                <Loader2 className="h-4 w-4 animate-spin" />
              ) : (
                <Cpu className="h-4 w-4" />
              )}
              {activeModelSlot === 'local' ? '更新当前本地模型' : '切换为本地模型'}
            </button>
          </div>
        </SectionCard>

        <SectionCard
          title="ASR（默认继承主模型 API Key）"
          description="语音面试实时识别链路；输入 API Key 后会覆盖继承值。"
        >
        <div className="grid gap-4 2xl:grid-cols-2">
          <Field label="WebSocket URL">
            <input
              className={inputClass()}
              value={asrForm.url ?? ''}
              onChange={e => setAsrForm(prev => ({ ...prev, url: e.target.value }))}
            />
          </Field>
          <Field label="模型">
            <input
              className={inputClass()}
              value={asrForm.model ?? ''}
              onChange={e => setAsrForm(prev => ({ ...prev, model: e.target.value }))}
            />
          </Field>
          <Field label="语言">
            <input
              className={inputClass()}
              value={asrForm.language ?? ''}
              onChange={e => setAsrForm(prev => ({ ...prev, language: e.target.value }))}
            />
          </Field>
          <Field label="格式">
            <input
              className={inputClass()}
              value={asrForm.format ?? ''}
              onChange={e => setAsrForm(prev => ({ ...prev, format: e.target.value }))}
            />
          </Field>
          <Field label="采样率">
            <input
              className={inputClass()}
              type="number"
              value={asrForm.sampleRate ?? ''}
              onChange={e => setAsrForm(prev => ({ ...prev, sampleRate: toOptionalNumber(e.target.value) }))}
            />
          </Field>
          <Field label="API Key 覆盖">
            <input
              className={inputClass()}
              type="password"
              placeholder={asrConfig?.maskedApiKey || '留空继承主模型 API Key'}
              onChange={e => setAsrForm(prev => ({ ...prev, apiKey: e.target.value }))}
            />
          </Field>
          <Field label="Turn Detection 类型">
            <input
              className={inputClass()}
              value={asrForm.turnDetectionType ?? ''}
              onChange={e => setAsrForm(prev => ({ ...prev, turnDetectionType: e.target.value }))}
            />
          </Field>
          <Field label="静音阈值毫秒">
            <input
              className={inputClass()}
              type="number"
              value={asrForm.turnDetectionSilenceDurationMs ?? ''}
              onChange={e => setAsrForm(prev => ({
                ...prev,
                turnDetectionSilenceDurationMs: toOptionalNumber(e.target.value),
              }))}
            />
          </Field>
        </div>
        <label className="mt-4 flex items-center gap-3 text-sm text-slate-700 dark:text-slate-200">
          <input
            type="checkbox"
            checked={Boolean(asrForm.enableTurnDetection)}
            onChange={e => setAsrForm(prev => ({ ...prev, enableTurnDetection: e.target.checked }))}
          />
          启用 Turn Detection
        </label>
        <div className="mt-5 flex flex-wrap gap-3">
          <button
            onClick={handleSaveAsr}
            disabled={savingAsr}
            className="inline-flex items-center gap-2 rounded-xl bg-primary-500 px-4 py-2.5 text-sm font-medium text-white hover:bg-primary-600 disabled:opacity-60"
          >
            {savingAsr ? <Loader2 className="h-4 w-4 animate-spin" /> : <Mic className="h-4 w-4" />}
            保存 ASR
          </button>
          <button
            onClick={handleTestAsr}
            disabled={testingAsr}
            className="inline-flex items-center gap-2 rounded-xl border border-slate-200 px-4 py-2.5 text-sm font-medium text-slate-700 hover:bg-slate-50 disabled:opacity-60 dark:border-slate-700 dark:text-slate-200 dark:hover:bg-slate-700/50"
          >
            {testingAsr ? <Loader2 className="h-4 w-4 animate-spin" /> : <Wifi className="h-4 w-4" />}
            测试 ASR
          </button>
          <button
            onClick={handleClearAsrKey}
            className="inline-flex items-center gap-2 rounded-xl border border-slate-200 px-4 py-2.5 text-sm font-medium text-slate-700 hover:bg-slate-50 dark:border-slate-700 dark:text-slate-200 dark:hover:bg-slate-700/50"
          >
            <KeyRound className="h-4 w-4" />
            继承主模型 Key
          </button>
        </div>
        {asrTest && (
          <p className={`mt-4 text-sm ${asrTest.success ? 'text-emerald-600 dark:text-emerald-300' : 'text-rose-600 dark:text-rose-300'}`}>
            {asrTest.message} {asrTest.model ? `(${asrTest.model})` : ''}
          </p>
        )}
      </SectionCard>

      <SectionCard
        title="TTS（默认继承主模型 API Key）"
        description="语音面试实时合成链路；输入 API Key 后会覆盖继承值。"
      >
        <div className="grid gap-4 2xl:grid-cols-2">
          <Field label="模型">
            <input
              className={inputClass()}
              value={ttsForm.model ?? ''}
              onChange={e => setTtsForm(prev => ({ ...prev, model: e.target.value }))}
            />
          </Field>
          <Field label="API Key 覆盖">
            <input
              className={inputClass()}
              type="password"
              placeholder={ttsConfig?.maskedApiKey || '留空继承主模型 API Key'}
              onChange={e => setTtsForm(prev => ({ ...prev, apiKey: e.target.value }))}
            />
          </Field>
          <Field label="音色">
            <input
              className={inputClass()}
              value={ttsForm.voice ?? ''}
              onChange={e => setTtsForm(prev => ({ ...prev, voice: e.target.value }))}
            />
          </Field>
          <Field label="格式">
            <input
              className={inputClass()}
              value={ttsForm.format ?? ''}
              onChange={e => setTtsForm(prev => ({ ...prev, format: e.target.value }))}
            />
          </Field>
          <Field label="采样率">
            <input
              className={inputClass()}
              type="number"
              value={ttsForm.sampleRate ?? ''}
              onChange={e => setTtsForm(prev => ({ ...prev, sampleRate: toOptionalNumber(e.target.value) }))}
            />
          </Field>
          <Field label="模式">
            <input
              className={inputClass()}
              value={ttsForm.mode ?? ''}
              onChange={e => setTtsForm(prev => ({ ...prev, mode: e.target.value }))}
            />
          </Field>
          <Field label="语言类型">
            <input
              className={inputClass()}
              value={ttsForm.languageType ?? ''}
              onChange={e => setTtsForm(prev => ({ ...prev, languageType: e.target.value }))}
            />
          </Field>
          <Field label="语速">
            <input
              className={inputClass()}
              type="number"
              step="0.1"
              value={ttsForm.speechRate ?? ''}
              onChange={e => setTtsForm(prev => ({ ...prev, speechRate: toOptionalNumber(e.target.value) }))}
            />
          </Field>
        </div>
        <div className="mt-5 flex flex-wrap gap-3">
          <button
            onClick={handleSaveTts}
            disabled={savingTts}
            className="inline-flex items-center gap-2 rounded-xl bg-primary-500 px-4 py-2.5 text-sm font-medium text-white hover:bg-primary-600 disabled:opacity-60"
          >
            {savingTts ? <Loader2 className="h-4 w-4 animate-spin" /> : <Volume2 className="h-4 w-4" />}
            保存 TTS
          </button>
          <button
            onClick={handleClearTtsKey}
            className="inline-flex items-center gap-2 rounded-xl border border-slate-200 px-4 py-2.5 text-sm font-medium text-slate-700 hover:bg-slate-50 dark:border-slate-700 dark:text-slate-200 dark:hover:bg-slate-700/50"
          >
            <KeyRound className="h-4 w-4" />
            继承主模型 Key
          </button>
        </div>
      </SectionCard>
      </div>
    </div>
  );
}
