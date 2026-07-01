export interface ModelSettings {
  baseUrl: string;
  maskedApiKey: string;
  chatModel: string;
  embeddingModel: string | null;
  embeddingDimensions: number | null;
  cloudEmbeddingBaseUrl: string | null;
  maskedCloudEmbeddingApiKey: string;
  cloudEmbeddingModel: string | null;
  cloudEmbeddingDimensions: number | null;
  temperature: number | null;
}

export interface ModelSettingsRequest {
  baseUrl?: string;
  apiKey?: string;
  chatModel?: string;
  embeddingModel?: string;
  embeddingDimensions?: number;
  cloudEmbeddingBaseUrl?: string;
  cloudEmbeddingApiKey?: string;
  cloudEmbeddingModel?: string;
  cloudEmbeddingDimensions?: number;
  temperature?: number;
}

export interface SettingsTestResult {
  success: boolean;
  message: string;
  model: string;
}

export interface AsrConfig {
  url: string;
  model: string;
  maskedApiKey: string;
  language: string;
  format: string;
  sampleRate: number;
  enableTurnDetection: boolean;
  turnDetectionType: string;
  turnDetectionThreshold: number;
  turnDetectionSilenceDurationMs: number;
}

export interface TtsConfig {
  model: string;
  maskedApiKey: string;
  voice: string;
  format: string;
  sampleRate: number;
  mode: string;
  languageType: string;
  speechRate: number;
  volume: number;
}

export interface AsrConfigRequest {
  url?: string;
  model?: string;
  apiKey?: string;
  language?: string;
  format?: string;
  sampleRate?: number;
  enableTurnDetection?: boolean;
  turnDetectionType?: string;
  turnDetectionThreshold?: number;
  turnDetectionSilenceDurationMs?: number;
}

export interface TtsConfigRequest {
  model?: string;
  apiKey?: string;
  voice?: string;
  format?: string;
  sampleRate?: number;
  mode?: string;
  languageType?: string;
  speechRate?: number;
  volume?: number;
}
