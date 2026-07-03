package interview.guide.modules.aisettings.service;

import interview.guide.common.config.AiProperties;
import interview.guide.common.config.AiProperties.CloudEmbeddingConfig;
import interview.guide.common.config.ConfigPlaceholderResolver;
import interview.guide.infrastructure.mapper.AiSettingsMapper;
import interview.guide.modules.aisettings.model.UserAiSettingsEntity;
import interview.guide.modules.aisettings.repository.UserAiSettingsRepository;
import interview.guide.modules.voiceinterview.config.VoiceInterviewProperties;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 用户AI配置解析器
 * 负责解析用户的AI配置，优先级为用户自定义配置 > 全局默认配置，
 * 支持配置占位符解析、本地/云端模型识别和API密钥回退机制
 */
@Service
@RequiredArgsConstructor
public class AiSettingsResolver {

    private final AiProperties aiProperties; // AI相关全局属性
    private final VoiceInterviewProperties voiceProperties; // 语音面试配置属性
    private final UserAiSettingsRepository settingsRepository; // 用户AI配置数据访问层
    private final AiSettingsMapper aiSettingsMapper; // AI配置实体映射器

    /**
     * 解析用户的大模型配置
     * 优先使用用户自定义配置，不存在时使用全局默认配置
     */
    public ModelConfigSnapshot resolveModel(String userId) {
        return settingsRepository.findByUserId(trimOrNull(userId))
                .map(this::modelFromEntity)
                .orElseGet(this::defaultModel);
    }

    /**
     * 解析用户的Embedding模型配置
     * 本地模型和云端模型使用不同的Embedding配置来源
     */
    public ModelConfigSnapshot resolveEmbeddingModel(String userId) {
        return settingsRepository.findByUserId(trimOrNull(userId))
                .map(this::embeddingModelFromEntity)
                .orElseGet(this::defaultCloudEmbeddingModel);
    }

    /**
     * 解析用户的语音识别配置
     * API密钥优先使用ASR专用密钥，其次回退到大模型API密钥
     */
    public AsrConfigSnapshot resolveAsr(String userId) {
        Optional<UserAiSettingsEntity> settings = settingsRepository.findByUserId(trimOrNull(userId));
        ModelConfigSnapshot model = settings.map(this::modelFromEntity).orElseGet(this::defaultModel);
        return settings.map(entity -> asrFromEntity(entity, model.apiKey()))
                .orElseGet(() -> defaultAsr(model.apiKey()));
    }

    /**
     * 解析用户的语音合成配置
     * API密钥优先使用TTS专用密钥，其次回退到大模型API密钥
     */
    public TtsConfigSnapshot resolveTts(String userId) {
        Optional<UserAiSettingsEntity> settings = settingsRepository.findByUserId(trimOrNull(userId));
        ModelConfigSnapshot model = settings.map(this::modelFromEntity).orElseGet(this::defaultModel);
        return settings.map(entity -> ttsFromEntity(entity, model.apiKey()))
                .orElseGet(() -> defaultTts(model.apiKey()));
    }

    /**
     * 为用户构建默认配置实体
     * 用于首次保存时将全局默认值写入数据库，通过Mapper统一转换
     */
    public UserAiSettingsEntity buildDefaultEntity(String userId) {
        ModelConfigSnapshot model = defaultModel();
        ModelConfigSnapshot cloudEmbedding = defaultCloudEmbeddingModel();
        AsrConfigSnapshot asr = defaultAsr(model.apiKey());
        TtsConfigSnapshot tts = defaultTts(model.apiKey());
        return aiSettingsMapper.toUserAiSettingsEntity(userId, model, cloudEmbedding, asr, tts);
    }

    /**
     * 从实体对象解析大模型配置
     * 本地模型时Embedding使用大模型配置，云端模型时使用独立的云端Embedding配置
     */
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

    /**
     * 从实体对象解析Embedding模型配置
     * 云端模型时与大模型共用配置，本地模型时使用独立的云端Embedding配置
     */
    private ModelConfigSnapshot embeddingModelFromEntity(UserAiSettingsEntity entity) {
        ModelConfigSnapshot defaults = defaultCloudEmbeddingModel();
        ModelConfigSnapshot model = modelFromEntity(entity);
        // 云端模型：Embedding与大模型共用baseUrl和apiKey
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

        // 本地模型：使用独立的云端Embedding配置
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

    /**
     * 获取全局默认大模型配置
     */
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

    /**
     * 获取全局默认云端Embedding配置
     * 云端模型时尝试复用大模型的baseUrl和apiKey
     */
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
                        isLocalBaseUrl(model.baseUrl())
                                ? aiProperties.getEmbeddingDimensions()
                                : model.embeddingDimensions()
                ),
                model.temperature()
        );
    }

    /**
     * 从实体对象解析语音识别配置
     */
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

    /**
     * 获取默认语音识别配置
     */
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

    /**
     * 从实体对象解析语音合成配置
     */
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

    /**
     * 获取默认语音合成配置
     */
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

    /**
     * 解析语音服务的API密钥
     * 优先使用专用密钥，未配置时回退到大模型API密钥
     */
    private String resolveVoiceApiKey(String overrideApiKey, String modelApiKey) {
        String override = trimOrNull(resolve(overrideApiKey));
        return override != null ? override : trimOrNull(modelApiKey);
    }

    /**
     * 解析配置值，优先使用第一个有效值，否则使用回退值
     * 有效值指非空且不包含未解析的占位符${...}
     */
    private String firstResolved(String value, String fallback) {
        String resolved = trimOrNull(resolve(value));
        if (resolved == null || resolved.contains("${")) {
            return fallback;
        }
        return resolved;
    }

    /**
     * 标准化大模型API密钥
     * 本地模型无密钥时自动使用"ollama"
     */
    private String normalizeModelApiKey(String baseUrl, String apiKey) {
        String normalized = trimOrNull(apiKey);
        if (normalized == null && isLocalBaseUrl(baseUrl)) {
            return "ollama";
        }
        return normalized;
    }

    /**
     * 解析云端模型的API密钥
     * 验证密钥有效性，排除本地模型专用的"ollama"密钥
     */
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

    /**
     * 判断云端模型的API密钥是否无效
     * "ollama"是本地模型专用密钥，不能用于云端
     */
    private boolean isInvalidRemoteApiKey(String baseUrl, String apiKey) {
        String normalized = trimOrNull(apiKey);
        return !isLocalBaseUrl(baseUrl) && "ollama".equalsIgnoreCase(normalized);
    }

    /**
     * 判断是否为本地模型地址
     */
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

    /**
     * 解析Embedding维度，未配置时使用全局默认值
     */
    private Integer resolveEmbeddingDimensions(Integer configuredDimensions) {
        return resolveEmbeddingDimensions(configuredDimensions, aiProperties.getEmbeddingDimensions());
    }

    /**
     * 解析Embedding维度，优先使用配置值，否则使用回退值
     */
    private Integer resolveEmbeddingDimensions(Integer configuredDimensions, Integer fallbackDimensions) {
        if (configuredDimensions != null && configuredDimensions > 0) {
            return configuredDimensions;
        }
        return fallbackDimensions;
    }

    /**
     * 解析配置占位符
     */
    private String resolve(String value) {
        return ConfigPlaceholderResolver.resolve(value);
    }

    /**
     * 去除字符串首尾空格，空字符串返回null
     */
    private String trimOrNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * 大模型配置快照（不可变）
     */
    public record ModelConfigSnapshot(
            String baseUrl, // API基础地址
            String apiKey, // API密钥
            String chatModel, // 对话模型名称
            String embeddingModel, // Embedding模型名称
            Integer embeddingDimensions, // Embedding向量维度
            Double temperature // 生成温度参数
    ) {
    }

    /**
     * 语音识别配置快照（不可变）
     */
    public record AsrConfigSnapshot(
            String url, // WebSocket服务地址
            String model, // 识别模型名称
            String apiKey, // API密钥
            String language, // 识别语言
            String format, // 音频格式
            Integer sampleRate, // 采样率
            boolean enableTurnDetection, // 是否启用语音分段检测
            String turnDetectionType, // 分段检测类型
            Float turnDetectionThreshold, // 分段检测阈值
            Integer turnDetectionSilenceDurationMs // 静音检测时长（毫秒）
    ) {
    }

    /**
     * 语音合成配置快照（不可变）
     */
    public record TtsConfigSnapshot(
            String model, // 合成模型名称
            String apiKey, // API密钥
            String voice, // 音色
            String format, // 音频格式
            Integer sampleRate, // 采样率
            String mode, // 合成模式
            String languageType, // 语言类型
            Float speechRate, // 语速
            Integer volume // 音量
    ) {
    }
}