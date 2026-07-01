package interview.guide.modules.aisettings.service;

import interview.guide.common.ai.AiClientFactory;
import interview.guide.common.auth.CurrentUserContext;
import interview.guide.common.config.AiProperties;
import interview.guide.common.config.ConfigPlaceholderResolver;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.aisettings.dto.AsrConfigDTO;
import interview.guide.modules.aisettings.dto.AsrConfigRequest;
import interview.guide.modules.aisettings.dto.ModelSettingsDTO;
import interview.guide.modules.aisettings.dto.ModelSettingsRequest;
import interview.guide.modules.aisettings.dto.SettingsTestResult;
import interview.guide.modules.aisettings.dto.TtsConfigDTO;
import interview.guide.modules.aisettings.dto.TtsConfigRequest;
import interview.guide.modules.aisettings.model.UserAiSettingsEntity;
import interview.guide.modules.aisettings.repository.UserAiSettingsRepository;
import interview.guide.modules.aisettings.service.AiSettingsResolver.AsrConfigSnapshot;
import interview.guide.modules.aisettings.service.AiSettingsResolver.ModelConfigSnapshot;
import interview.guide.modules.aisettings.service.AiSettingsResolver.TtsConfigSnapshot;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

@Service
@Slf4j
@RequiredArgsConstructor
public class AiSettingsService {

    private final AiProperties aiProperties;
    private final AiClientFactory aiClientFactory;
    private final AiSettingsResolver settingsResolver;
    private final UserAiSettingsRepository settingsRepository;
    private final ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock();

    public ModelSettingsDTO getModelSettings() {
        String userId = CurrentUserContext.getRequiredUserId();
        rwLock.readLock().lock();
        try {
            ModelConfigSnapshot config = settingsResolver.resolveModel(userId);
            ModelConfigSnapshot embeddingConfig = settingsResolver.resolveEmbeddingModel(userId);
            return ModelSettingsDTO.builder()
                    .baseUrl(config.baseUrl())
                    .maskedApiKey(maskApiKey(config.apiKey()))
                    .chatModel(config.chatModel())
                    .embeddingModel(embeddingConfig.embeddingModel())
                    .embeddingDimensions(embeddingConfig.embeddingDimensions())
                    .cloudEmbeddingBaseUrl(embeddingConfig.baseUrl())
                    .maskedCloudEmbeddingApiKey(maskApiKey(embeddingConfig.apiKey()))
                    .cloudEmbeddingModel(embeddingConfig.embeddingModel())
                    .cloudEmbeddingDimensions(embeddingConfig.embeddingDimensions())
                    .temperature(config.temperature())
                    .build();
        } finally {
            rwLock.readLock().unlock();
        }
    }

    public SettingsTestResult testModelSettings() {
        String userId = CurrentUserContext.getRequiredUserId();
        rwLock.readLock().lock();
        try {
            return doTestModel(settingsResolver.resolveModel(userId));
        } finally {
            rwLock.readLock().unlock();
        }
    }

    public AsrConfigDTO getAsrConfig() {
        String userId = CurrentUserContext.getRequiredUserId();
        rwLock.readLock().lock();
        try {
            AsrConfigSnapshot config = settingsResolver.resolveAsr(userId);
            return AsrConfigDTO.builder()
                    .url(config.url())
                    .model(config.model())
                    .maskedApiKey(maskApiKey(config.apiKey()))
                    .language(config.language())
                    .format(config.format())
                    .sampleRate(nullToInt(config.sampleRate()))
                    .enableTurnDetection(config.enableTurnDetection())
                    .turnDetectionType(config.turnDetectionType())
                    .turnDetectionThreshold(nullToFloat(config.turnDetectionThreshold()))
                    .turnDetectionSilenceDurationMs(nullToInt(config.turnDetectionSilenceDurationMs()))
                    .build();
        } finally {
            rwLock.readLock().unlock();
        }
    }

    public SettingsTestResult testAsrConfig() {
        String userId = CurrentUserContext.getRequiredUserId();
        rwLock.readLock().lock();
        try {
            AsrConfigSnapshot config = settingsResolver.resolveAsr(userId);
            try (Socket socket = new Socket()) {
                URI wsUri = URI.create(config.url());
                String host = wsUri.getHost();
                int port = wsUri.getPort() > 0 ? wsUri.getPort() : ("wss".equals(wsUri.getScheme()) ? 443 : 80);
                socket.connect(new InetSocketAddress(host, port), 5000);
                return SettingsTestResult.builder()
                        .success(true)
                        .message("ASR WebSocket 连接成功: " + host)
                        .model(config.model())
                        .build();
            } catch (Exception e) {
                return SettingsTestResult.builder()
                        .success(false)
                        .message("ASR 连接失败: " + e.getMessage())
                        .model(config.model())
                        .build();
            }
        } finally {
            rwLock.readLock().unlock();
        }
    }

    public TtsConfigDTO getTtsConfig() {
        String userId = CurrentUserContext.getRequiredUserId();
        rwLock.readLock().lock();
        try {
            TtsConfigSnapshot config = settingsResolver.resolveTts(userId);
            return TtsConfigDTO.builder()
                    .model(config.model())
                    .maskedApiKey(maskApiKey(config.apiKey()))
                    .voice(config.voice())
                    .format(config.format())
                    .sampleRate(nullToInt(config.sampleRate()))
                    .mode(config.mode())
                    .languageType(config.languageType())
                    .speechRate(nullToFloat(config.speechRate()))
                    .volume(nullToInt(config.volume()))
                    .build();
        } finally {
            rwLock.readLock().unlock();
        }
    }

    @Transactional
    public void updateModelSettings(ModelSettingsRequest request) {
        String userId = CurrentUserContext.getRequiredUserId();
        rwLock.writeLock().lock();
        try {
            UserAiSettingsEntity entity = getOrCreateUserSettings(userId);
            preserveCurrentCloudConfig(entity);
            if (request.baseUrl() != null) {
                entity.setModelBaseUrl(requireNonBlank(request.baseUrl(), "baseUrl"));
            }
            if (request.chatModel() != null) {
                entity.setChatModel(requireNonBlank(request.chatModel(), "chatModel"));
            }
            if (request.embeddingModel() != null) {
                entity.setEmbeddingModel(trimOrNull(request.embeddingModel()));
            }
            if (request.embeddingDimensions() != null) {
                entity.setEmbeddingDimensions(resolveEmbeddingDimensions(request.embeddingDimensions()));
            }
            if (request.cloudEmbeddingBaseUrl() != null) {
                entity.setCloudEmbeddingBaseUrl(requireNonBlank(
                        request.cloudEmbeddingBaseUrl(),
                        "cloudEmbeddingBaseUrl"
                ));
            }
            if (request.cloudEmbeddingApiKey() != null) {
                entity.setCloudEmbeddingApiKey(trimOrNull(request.cloudEmbeddingApiKey()));
            }
            if (request.cloudEmbeddingModel() != null) {
                entity.setCloudEmbeddingModel(trimOrNull(request.cloudEmbeddingModel()));
            }
            if (request.cloudEmbeddingDimensions() != null) {
                entity.setCloudEmbeddingDimensions(resolveEmbeddingDimensions(request.cloudEmbeddingDimensions()));
            }
            if (request.temperature() != null) {
                entity.setTemperature(request.temperature());
            }
            if (request.apiKey() != null) {
                String apiKey = trimOrNull(request.apiKey());
                if (apiKey != null) {
                    entity.setModelApiKey(apiKey);
                } else if (isLocalBaseUrl(entity.getModelBaseUrl())) {
                    entity.setModelApiKey("ollama");
                }
            }

            restoreCloudApiKeyIfNeeded(entity, request);
            syncCloudEmbeddingConfig(entity);
            validateModelConfig(entity);
            validateCloudApiKey(entity);
            validateCloudEmbeddingConfig(entity);
            settingsRepository.save(entity);
            reloadAiClientsAfterCommit(userId);
            log.info("Updated user AI model settings: userId={}", userId);
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    @Transactional
    public void updateAsrConfig(AsrConfigRequest request) {
        String userId = CurrentUserContext.getRequiredUserId();
        rwLock.writeLock().lock();
        try {
            UserAiSettingsEntity entity = getOrCreateUserSettings(userId);
            if (request.url() != null) {
                entity.setAsrUrl(requireNonBlank(request.url(), "url"));
            }
            if (request.model() != null) {
                entity.setAsrModel(requireNonBlank(request.model(), "model"));
            }
            if (request.apiKey() != null) {
                entity.setAsrApiKey(trimOrNull(request.apiKey()));
            }
            if (request.language() != null) {
                entity.setAsrLanguage(requireNonBlank(request.language(), "language"));
            }
            if (request.format() != null) {
                entity.setAsrFormat(requireNonBlank(request.format(), "format"));
            }
            if (request.sampleRate() != null) {
                entity.setAsrSampleRate(request.sampleRate());
            }
            if (request.enableTurnDetection() != null) {
                entity.setAsrEnableTurnDetection(request.enableTurnDetection());
            }
            if (request.turnDetectionType() != null) {
                entity.setAsrTurnDetectionType(requireNonBlank(request.turnDetectionType(), "turnDetectionType"));
            }
            if (request.turnDetectionThreshold() != null) {
                entity.setAsrTurnDetectionThreshold(request.turnDetectionThreshold());
            }
            if (request.turnDetectionSilenceDurationMs() != null) {
                entity.setAsrTurnDetectionSilenceDurationMs(request.turnDetectionSilenceDurationMs());
            }

            settingsRepository.save(entity);
            log.info("Updated user ASR settings: userId={}", userId);
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    @Transactional
    public void updateTtsConfig(TtsConfigRequest request) {
        String userId = CurrentUserContext.getRequiredUserId();
        rwLock.writeLock().lock();
        try {
            UserAiSettingsEntity entity = getOrCreateUserSettings(userId);
            if (request.model() != null) {
                entity.setTtsModel(requireNonBlank(request.model(), "model"));
            }
            if (request.apiKey() != null) {
                entity.setTtsApiKey(trimOrNull(request.apiKey()));
            }
            if (request.voice() != null) {
                entity.setTtsVoice(requireNonBlank(request.voice(), "voice"));
            }
            if (request.format() != null) {
                entity.setTtsFormat(requireNonBlank(request.format(), "format"));
            }
            if (request.sampleRate() != null) {
                entity.setTtsSampleRate(request.sampleRate());
            }
            if (request.mode() != null) {
                entity.setTtsMode(requireNonBlank(request.mode(), "mode"));
            }
            if (request.languageType() != null) {
                entity.setTtsLanguageType(requireNonBlank(request.languageType(), "languageType"));
            }
            if (request.speechRate() != null) {
                entity.setTtsSpeechRate(request.speechRate());
            }
            if (request.volume() != null) {
                entity.setTtsVolume(request.volume());
            }

            settingsRepository.save(entity);
            log.info("Updated user TTS settings: userId={}", userId);
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    private UserAiSettingsEntity getOrCreateUserSettings(String userId) {
        return settingsRepository.findByUserId(userId)
                .orElseGet(() -> settingsResolver.buildDefaultEntity(userId));
    }

    private void preserveCurrentCloudConfig(UserAiSettingsEntity entity) {
        if (isLocalBaseUrl(entity.getModelBaseUrl())) {
            return;
        }
        if (!isMissingResolvedValue(entity.getModelBaseUrl())) {
            entity.setCloudEmbeddingBaseUrl(entity.getModelBaseUrl());
        }
        if (!isMissingResolvedValue(entity.getModelApiKey())
                && !isInvalidRemoteApiKey(entity.getModelBaseUrl(), entity.getModelApiKey())) {
            entity.setCloudEmbeddingApiKey(entity.getModelApiKey());
        }
        if (!isMissingResolvedValue(entity.getEmbeddingModel())) {
            entity.setCloudEmbeddingModel(entity.getEmbeddingModel());
        }
        if (entity.getEmbeddingDimensions() != null && entity.getEmbeddingDimensions() > 0) {
            entity.setCloudEmbeddingDimensions(entity.getEmbeddingDimensions());
        }
    }

    private void restoreCloudApiKeyIfNeeded(UserAiSettingsEntity entity, ModelSettingsRequest request) {
        if (isLocalBaseUrl(entity.getModelBaseUrl())) {
            if (isMissingResolvedValue(entity.getModelApiKey())) {
                entity.setModelApiKey("ollama");
            }
            return;
        }

        if (request.apiKey() != null && trimOrNull(request.apiKey()) != null) {
            return;
        }
        if (!isMissingResolvedValue(entity.getModelApiKey())
                && !isInvalidRemoteApiKey(entity.getModelBaseUrl(), entity.getModelApiKey())) {
            return;
        }

        String preservedApiKey = trimOrNull(ConfigPlaceholderResolver.resolve(entity.getCloudEmbeddingApiKey()));
        if (preservedApiKey != null
                && !preservedApiKey.contains("${")
                && !isInvalidRemoteApiKey(entity.getModelBaseUrl(), preservedApiKey)) {
            entity.setModelApiKey(preservedApiKey);
        }
    }

    private void syncCloudEmbeddingConfig(UserAiSettingsEntity entity) {
        if (!isLocalBaseUrl(entity.getModelBaseUrl())) {
            ModelConfigSnapshot defaults = settingsResolver.resolveEmbeddingModel(null);
            entity.setCloudEmbeddingBaseUrl(entity.getModelBaseUrl());
            entity.setCloudEmbeddingApiKey(entity.getModelApiKey());
            if (isMissingResolvedValue(entity.getEmbeddingModel())) {
                entity.setCloudEmbeddingModel(defaults.embeddingModel());
            } else {
                entity.setCloudEmbeddingModel(entity.getEmbeddingModel());
            }
            if (entity.getEmbeddingDimensions() == null || entity.getEmbeddingDimensions() <= 0) {
                entity.setCloudEmbeddingDimensions(defaults.embeddingDimensions());
            } else {
                entity.setCloudEmbeddingDimensions(entity.getEmbeddingDimensions());
            }
            return;
        }

        ModelConfigSnapshot defaults = settingsResolver.resolveEmbeddingModel(null);
        if (isMissingResolvedValue(entity.getCloudEmbeddingBaseUrl())) {
            entity.setCloudEmbeddingBaseUrl(defaults.baseUrl());
        }
        if (isMissingResolvedValue(entity.getCloudEmbeddingApiKey())
                || isInvalidRemoteApiKey(entity.getCloudEmbeddingBaseUrl(), entity.getCloudEmbeddingApiKey())) {
            entity.setCloudEmbeddingApiKey(defaults.apiKey());
        }
        if (isMissingResolvedValue(entity.getCloudEmbeddingModel())) {
            entity.setCloudEmbeddingModel(defaults.embeddingModel());
        }
        if (entity.getCloudEmbeddingDimensions() == null || entity.getCloudEmbeddingDimensions() <= 0) {
            entity.setCloudEmbeddingDimensions(defaults.embeddingDimensions());
        }
    }

    private void reloadAiClientsAfterCommit(String userId) {
        Runnable reloadTask = () -> {
            aiClientFactory.reload(userId);
            aiClientFactory.reload(null);
        };
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            reloadTask.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                reloadTask.run();
            }
        });
    }

    private void validateModelConfig(UserAiSettingsEntity entity) {
        if (isMissingResolvedValue(entity.getModelBaseUrl())
                || isMissingResolvedValue(entity.getChatModel())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "AI 模型必填配置不能为空");
        }
    }

    private void validateCloudApiKey(UserAiSettingsEntity entity) {
        if (!isLocalBaseUrl(entity.getModelBaseUrl())
                && (isMissingResolvedValue(entity.getModelApiKey())
                || isInvalidRemoteApiKey(entity.getModelBaseUrl(), entity.getModelApiKey()))) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "云端模型 API Key 不能为空");
        }
    }

    private void validateCloudEmbeddingConfig(UserAiSettingsEntity entity) {
        if (isMissingResolvedValue(entity.getCloudEmbeddingBaseUrl())
                || isLocalBaseUrl(entity.getCloudEmbeddingBaseUrl())
                || isInvalidRemoteApiKey(entity.getCloudEmbeddingBaseUrl(), entity.getCloudEmbeddingApiKey())
                || isMissingResolvedValue(entity.getCloudEmbeddingApiKey())
                || isMissingResolvedValue(entity.getCloudEmbeddingModel())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "云端 Embedding 配置不能为空");
        }
    }

    private SettingsTestResult doTestModel(ModelConfigSnapshot config) {
        try {
            String baseUrl = config.baseUrl();
            String apiKey = config.apiKey();
            String chatModel = config.chatModel();
            if (isMissingResolvedValue(baseUrl)
                    || (!isLocalBaseUrl(baseUrl) && isMissingResolvedValue(apiKey))
                    || isMissingResolvedValue(chatModel)) {
                return SettingsTestResult.builder()
                        .success(false)
                        .message("AI 模型关键配置不能为空")
                        .model(chatModel)
                        .build();
            }

            SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
            requestFactory.setConnectTimeout(5000);
            requestFactory.setReadTimeout(10000);
            RestClient restClient = RestClient.builder()
                    .defaultHeader("Authorization", "Bearer " + normalizeApiKey(baseUrl, apiKey))
                    .requestFactory(requestFactory)
                    .build();

            Map<String, Object> requestBody = Map.of(
                    "model", chatModel,
                    "messages", List.of(Map.of("role", "user", "content", "ping")),
                    "max_tokens", 1
            );
            String lastFailureMessage = "Unknown error";
            for (String targetUrl : buildConnectivityTestUrls(baseUrl)) {
                try {
                    restClient.post()
                            .uri(URI.create(targetUrl))
                            .body(requestBody)
                            .retrieve()
                            .toEntity(String.class);
                    return SettingsTestResult.builder()
                            .success(true)
                            .message("连接成功")
                            .model(chatModel)
                            .build();
                } catch (RestClientResponseException e) {
                    lastFailureMessage = "HTTP " + e.getStatusCode().value() + " on " + targetUrl;
                } catch (Exception e) {
                    lastFailureMessage = e.getClass().getSimpleName() + " on " + targetUrl + ": " + e.getMessage();
                }
            }
            return SettingsTestResult.builder()
                    .success(false)
                    .message("连接失败: " + lastFailureMessage)
                    .model(chatModel)
                    .build();
        } catch (Exception e) {
            return SettingsTestResult.builder()
                    .success(false)
                    .message("连接失败: " + e.getMessage())
                    .model(config.chatModel())
                    .build();
        }
    }

    private List<String> buildConnectivityTestUrls(String baseUrl) {
        String normalized = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        Set<String> targetUrls = new LinkedHashSet<>();
        targetUrls.add(normalized + "/chat/completions");
        if (!normalized.endsWith("/v1")) {
            targetUrls.add(normalized + "/v1/chat/completions");
        }
        return new ArrayList<>(targetUrls);
    }

    private String requireNonBlank(String value, String fieldName) {
        String trimmed = trimOrNull(value);
        if (trimmed == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, fieldName + " 不能为空");
        }
        return trimmed;
    }

    private boolean isMissingResolvedValue(String value) {
        String resolved = ConfigPlaceholderResolver.resolve(value);
        return trimOrNull(resolved) == null || resolved.contains("${");
    }

    private String normalizeApiKey(String baseUrl, String apiKey) {
        String normalized = trimOrNull(apiKey);
        if (normalized == null && isLocalBaseUrl(baseUrl)) {
            return "ollama";
        }
        return normalized;
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
        if (configuredDimensions != null && configuredDimensions > 0) {
            return configuredDimensions;
        }
        return aiProperties.getEmbeddingDimensions();
    }

    private int nullToInt(Integer value) {
        return value == null ? 0 : value;
    }

    private float nullToFloat(Float value) {
        return value == null ? 0F : value;
    }

    private String trimOrNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String maskApiKey(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            return "";
        }
        if (apiKey.length() <= 8) {
            return "****";
        }
        return apiKey.substring(0, 4) + "****" + apiKey.substring(apiKey.length() - 4);
    }
}
