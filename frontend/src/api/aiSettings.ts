import { request } from './request';
import type {
  AsrConfig,
  AsrConfigRequest,
  ModelSettings,
  ModelSettingsRequest,
  SettingsTestResult,
  TtsConfig,
  TtsConfigRequest,
} from '../types/aiSettings';

export const aiSettingsApi = {
  getModelSettings: () =>
    request.get<ModelSettings>('/api/settings/ai/model'),

  updateModelSettings: (data: ModelSettingsRequest) =>
    request.put<void>('/api/settings/ai/model', data),

  testModelSettings: () =>
    request.post<SettingsTestResult>('/api/settings/ai/model/test'),

  getAsrConfig: () =>
    request.get<AsrConfig>('/api/settings/ai/voice/asr'),

  updateAsrConfig: (data: AsrConfigRequest) =>
    request.put<void>('/api/settings/ai/voice/asr', data),

  getTtsConfig: () =>
    request.get<TtsConfig>('/api/settings/ai/voice/tts'),

  updateTtsConfig: (data: TtsConfigRequest) =>
    request.put<void>('/api/settings/ai/voice/tts', data),

  testAsr: () =>
    request.post<SettingsTestResult>('/api/settings/ai/voice/asr/test'),
};
