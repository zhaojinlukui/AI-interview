package interview.guide.modules.aisettings.service;

import interview.guide.common.config.AiProperties;
import interview.guide.common.config.AiProperties.CloudEmbeddingConfig;
import interview.guide.common.config.ConfigPlaceholderResolver;
import interview.guide.modules.aisettings.model.UserAiSettingsEntity;
import interview.guide.modules.aisettings.repository.UserAiSettingsRepository;
import interview.guide.modules.voiceinterview.config.VoiceInterviewProperties;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AiSettingsResolver {

    private final AiProperties aiProperties;
    private final VoiceInterviewProperties voiceProperties;
    private final UserAiSettingsRepository settingsRepository;

    public ModelConfigSnapshot resolveModel(String userId) {
        return settingsRepository.findByUserId(trimOrNull(userId))
                .map(this::modelFromEntity)
                .orElseGet(this::defaultModel);
    }

    public ModelConfigSnapshot resolveEmbeddingModel(String userId) {
        return settingsRepository.findByUserId(trimOrNull(userId))
                .map(this::embeddingModelFromEntity)
                .orElseGet(this::defaultCloudEmbeddingModel);
    }

    public AsrConfigSnapshot resolveAsr(String userId) {
        Optional<UserAiSettingsEntity> settings = settingsRepository.findByUserId(trimOrNull(userId));
        ModelConfigSnapshot model = settings.map(this::modelFromEntity).orElseGet(this::defaultModel);
        return settings.map(entity -> asrFromEntity(entity, model.apiKey())).orElseGet(() -> defaultAsr(model.apiKey()));
    }

    public TtsConfigSnapshot resolveTts(String userId) {
        Optional<UserAiSettingsEntity> settings = settingsRepository.findByUserId(trimOrNull(userId));
        ModelConfigSnapshot model = settings.map(this::modelFromEntity).orElseGet(this::defaultModel);
        return settings.map(entity -> ttsFromEntity(entity, model.apiKey())).orElseGet(() -> defaultTts(model.apiKey()));
    }

    public UserAiSettingsEntity buildDefaultEntity(String userId) {
        ModelConfigSnapshot model = defaultModel();
        ModelConfigSnapshot cloudEmbedding = defaultCloudEmbeddingModel();
        AsrConfigSnapshot asr = defaultAsr(model.apiKey());
        TtsConfigSnapshot tts = defaultTts(model.apiKey());
        return UserAiSettingsEntity.builder()
                .userId(userId)
                .modelBaseUrl(model.baseUrl())
                .modelApiKey(model.apiKey())
                .chatModel(model.chatModel())
                .embeddingModel(model.embeddingModel())
                .embeddingDimensions(model.embeddingDimensions())
                .cloudEmbeddingBaseUrl(cloudEmbedding.baseUrl())
                .cloudEmbeddingApiKey(cloudEmbedding.apiKey())
                .cloudEmbeddingModel(cloudEmbedding.embeddingModel())
                .cloudEmbeddingDimensions(cloudEmbedding.embeddingDimensions())
                .temperature(model.temperature())
                .asrUrl(asr.url())
                .asrModel(asr.model())
                .asrApiKey(null)
                .asrLanguage(asr.language())
                .asrFormat(asr.format())
                .asrSampleRate(asr.sampleRate())
                .asrEnableTurnDetection(asr.enableTurnDetection())
                .asrTurnDetectionType(asr.turnDetectionType())
                .asrTurnDetectionThreshold(asr.turnDetectionThreshold())
                .asrTurnDetectionSilenceDurationMs(asr.turnDetectionSilenceDurationMs())
                .ttsModel(tts.model())
                .ttsApiKey(null)
                .ttsVoice(tts.voice())
                .ttsFormat(tts.format())
                .ttsSampleRate(tts.sampleRate())
                .ttsMode(tts.mode())
                .ttsLanguageType(tts.languageType())
                .ttsSpeechRate(tts.speechRate())
                .ttsVolume(tts.volume())
                .build();
    }

    private ModelConfigSnapshot modelFromEntity(UserAiSettingsEntity entity) {
        ModelConfigSnapshot defaults = defaultModel();
        ModelConfigSnapshot embeddingDefaults = defaultCloudEmbeddingModel();
        String baseUrl = firstResolved(entity.getModelBaseUrl(), defaults.baseUrl());
        boolean localModel = isLocalBaseUrl(baseUrl);
        return new ModelConfigSnapshot(
                baseUrl,
                normalizeModelApiKey(baseUrl, firstResolved(entity.getModelApiKey(), defaults.apiKey())),
                firstResolved(entity.getChatModel(), defaults.chatModel()),
                firstResolved(
                        entity.getEmbeddingModel(),
                        localModel ? defaults.embeddingModel() : embeddingDefaults.embeddingModel()
                ),
                resolveEmbeddingDimensions(
                        entity.getEmbeddingDimensions(),
                        localModel ? defaults.embeddingDimensions() : embeddingDefaults.embeddingDimensions()
                ),
                entity.getTemperature() != null ? entity.getTemperature() : defaults.temperature()
        );
    }

    private ModelConfigSnapshot embeddingModelFromEntity(UserAiSettingsEntity entity) {
        ModelConfigSnapshot defaults = defaultCloudEmbeddingModel();
        ModelConfigSnapshot model = modelFromEntity(entity);
        if (!isLocalBaseUrl(model.baseUrl())) {
            return new ModelConfigSnapshot(
                    model.baseUrl(),
                    model.apiKey(),
                    model.chatModel(),
                    firstResolved(entity.getEmbeddingModel(), defaults.embeddingModel()),
                    resolveEmbeddingDimensions(entity.getEmbeddingDimensions(), defaults.embeddingDimensions()),
                    model.temperature()
            );
        }

        String baseUrl = firstResolved(entity.getCloudEmbeddingBaseUrl(), defaults.baseUrl());
        return new ModelConfigSnapshot(
                baseUrl,
                resolveRemoteApiKey(baseUrl, entity.getCloudEmbeddingApiKey(), defaults.apiKey()),
                defaults.chatModel(),
                firstResolved(entity.getCloudEmbeddingModel(), defaults.embeddingModel()),
                resolveEmbeddingDimensions(entity.getCloudEmbeddingDimensions(), defaults.embeddingDimensions()),
                defaults.temperature()
        );
    }

    private ModelConfigSnapshot defaultModel() {
        AiProperties.ModelConfig model = aiProperties.getModel();
        String baseUrl = resolve(model.getBaseUrl());
        return new ModelConfigSnapshot(
                baseUrl,
                normalizeModelApiKey(baseUrl, resolve(model.getApiKey())),
                resolve(model.getChatModel()),
                resolve(model.getEmbeddingModel()),
                resolveEmbeddingDimensions(model.getEmbeddingDimensions()),
                model.getTemperature()
        );
    }

    private ModelConfigSnapshot defaultCloudEmbeddingModel() {
        CloudEmbeddingConfig embedding = aiProperties.getCloudEmbedding();
        ModelConfigSnapshot model = defaultModel();
        String baseUrl = firstResolved(
                embedding.getBaseUrl(),
                isLocalBaseUrl(model.baseUrl()) ? null : model.baseUrl()
        );
        String apiKey = firstResolved(
                embedding.getApiKey(),
                isLocalBaseUrl(model.baseUrl()) ? null : model.apiKey()
        );
        return new ModelConfigSnapshot(
                baseUrl,
                resolveRemoteApiKey(baseUrl, apiKey, null),
                model.chatModel(),
                firstResolved(
                        embedding.getModel(),
                        isLocalBaseUrl(model.baseUrl()) ? null : model.embeddingModel()
                ),
                resolveEmbeddingDimensions(
                        embedding.getDimensions(),
                        isLocalBaseUrl(model.baseUrl()) ? aiProperties.getEmbeddingDimensions() : model.embeddingDimensions()
                ),
                model.temperature()
        );
    }

    private AsrConfigSnapshot asrFromEntity(UserAiSettingsEntity entity, String modelApiKey) {
        return new AsrConfigSnapshot(
                resolve(entity.getAsrUrl()),
                resolve(entity.getAsrModel()),
                resolveVoiceApiKey(entity.getAsrApiKey(), modelApiKey),
                resolve(entity.getAsrLanguage()),
                resolve(entity.getAsrFormat()),
                entity.getAsrSampleRate(),
                Boolean.TRUE.equals(entity.getAsrEnableTurnDetection()),
                resolve(entity.getAsrTurnDetectionType()),
                entity.getAsrTurnDetectionThreshold(),
                entity.getAsrTurnDetectionSilenceDurationMs()
        );
    }

    private AsrConfigSnapshot defaultAsr(String modelApiKey) {
        VoiceInterviewProperties.AsrConfig asr = voiceProperties.getQwen().getAsr();
        return new AsrConfigSnapshot(
                resolve(asr.getUrl()),
                resolve(asr.getModel()),
                resolveVoiceApiKey(asr.getApiKey(), modelApiKey),
                resolve(asr.getLanguage()),
                resolve(asr.getFormat()),
                asr.getSampleRate(),
                asr.isEnableTurnDetection(),
                resolve(asr.getTurnDetectionType()),
                asr.getTurnDetectionThreshold(),
                asr.getTurnDetectionSilenceDurationMs()
        );
    }

    private TtsConfigSnapshot ttsFromEntity(UserAiSettingsEntity entity, String modelApiKey) {
        return new TtsConfigSnapshot(
                resolve(entity.getTtsModel()),
                resolveVoiceApiKey(entity.getTtsApiKey(), modelApiKey),
                resolve(entity.getTtsVoice()),
                resolve(entity.getTtsFormat()),
                entity.getTtsSampleRate(),
                resolve(entity.getTtsMode()),
                resolve(entity.getTtsLanguageType()),
                entity.getTtsSpeechRate(),
                entity.getTtsVolume()
        );
    }

    private TtsConfigSnapshot defaultTts(String modelApiKey) {
        VoiceInterviewProperties.QwenTtsConfig tts = voiceProperties.getQwen().getTts();
        return new TtsConfigSnapshot(
                resolve(tts.getModel()),
                resolveVoiceApiKey(tts.getApiKey(), modelApiKey),
                resolve(tts.getVoice()),
                resolve(tts.getFormat()),
                tts.getSampleRate(),
                resolve(tts.getMode()),
                resolve(tts.getLanguageType()),
                tts.getSpeechRate(),
                tts.getVolume()
        );
    }

    private String resolveVoiceApiKey(String overrideApiKey, String modelApiKey) {
        String override = trimOrNull(resolve(overrideApiKey));
        return override != null ? override : trimOrNull(modelApiKey);
    }

    private String firstResolved(String value, String fallback) {
        String resolved = trimOrNull(resolve(value));
        if (resolved == null || resolved.contains("${")) {
            return fallback;
        }
        return resolved;
    }

    private String normalizeModelApiKey(String baseUrl, String apiKey) {
        String normalized = trimOrNull(apiKey);
        if (normalized == null && isLocalBaseUrl(baseUrl)) {
            return "ollama";
        }
        return normalized;
    }

    private String resolveRemoteApiKey(String baseUrl, String value, String fallback) {
        String resolved = firstResolved(value, null);
        if (resolved != null && !isInvalidRemoteApiKey(baseUrl, resolved)) {
            return normalizeModelApiKey(baseUrl, resolved);
        }

        String fallbackResolved = firstResolved(fallback, null);
        if (fallbackResolved != null && !isInvalidRemoteApiKey(baseUrl, fallbackResolved)) {
            return normalizeModelApiKey(baseUrl, fallbackResolved);
        }

        return null;
    }

    private boolean isInvalidRemoteApiKey(String baseUrl, String apiKey) {
        String normalized = trimOrNull(apiKey);
        return !isLocalBaseUrl(baseUrl) && "ollama".equalsIgnoreCase(normalized);
    }

    private boolean isLocalBaseUrl(String baseUrl) {
        if (baseUrl == null) {
            return false;
        }
        String normalized = baseUrl.toLowerCase();
        return normalized.contains("localhost")
                || normalized.contains("127.0.0.1")
                || normalized.contains("0.0.0.0")
                || normalized.contains("host.docker.internal");
    }

    private Integer resolveEmbeddingDimensions(Integer configuredDimensions) {
        return resolveEmbeddingDimensions(configuredDimensions, aiProperties.getEmbeddingDimensions());
    }

    private Integer resolveEmbeddingDimensions(Integer configuredDimensions, Integer fallbackDimensions) {
        if (configuredDimensions != null && configuredDimensions > 0) {
            return configuredDimensions;
        }
        return fallbackDimensions;
    }

    private String resolve(String value) {
        return ConfigPlaceholderResolver.resolve(value);
    }

    private String trimOrNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    public record ModelConfigSnapshot(
            String baseUrl,
            String apiKey,
            String chatModel,
            String embeddingModel,
            Integer embeddingDimensions,
            Double temperature
    ) {
    }

    public record AsrConfigSnapshot(
            String url,
            String model,
            String apiKey,
            String language,
            String format,
            Integer sampleRate,
            boolean enableTurnDetection,
            String turnDetectionType,
            Float turnDetectionThreshold,
            Integer turnDetectionSilenceDurationMs
    ) {
    }

    public record TtsConfigSnapshot(
            String model,
            String apiKey,
            String voice,
            String format,
            Integer sampleRate,
            String mode,
            String languageType,
            Float speechRate,
            Integer volume
    ) {
    }
}
