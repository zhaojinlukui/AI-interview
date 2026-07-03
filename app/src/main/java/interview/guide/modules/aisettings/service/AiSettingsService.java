package interview.guide.modules.aisettings.service;

import interview.guide.common.ai.AiClientFactory;
import interview.guide.common.auth.CurrentUserContext;
import interview.guide.common.config.AiProperties;
import interview.guide.common.config.ConfigPlaceholderResolver;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.infrastructure.mapper.AiSettingsMapper;
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

/**
 * AI配置管理服务
 * 负责用户级AI配置的查询、更新和连通性测试，
 * 包括大模型、语音识别和语音合成三类配置，
 * 使用读写锁保证并发安全，通过Mapper统一进行DTO与实体的转换
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class AiSettingsService {

    private final AiProperties aiProperties; // AI相关全局属性
    private final AiClientFactory aiClientFactory; // AI客户端工厂，配置变更后需重载
    private final AiSettingsResolver settingsResolver; // 用户AI配置解析器
    private final UserAiSettingsRepository settingsRepository; // 用户AI配置数据访问层
    private final AiSettingsMapper aiSettingsMapper; // AI配置实体与DTO映射器
    private final ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock(); // 读写锁，保证并发安全

    /**
     * 获取当前用户的大模型配置
     * 返回脱敏后的API密钥和完整的模型参数
     */
    public ModelSettingsDTO getModelSettings() {
        String userId = CurrentUserContext.getRequiredUserId();
        rwLock.readLock().lock();
        try {
            ModelConfigSnapshot config = settingsResolver.resolveModel(userId);
            ModelConfigSnapshot embeddingConfig = settingsResolver.resolveEmbeddingModel(userId);
            return aiSettingsMapper.toModelSettingsDTO(config, embeddingConfig);
        } finally {
            rwLock.readLock().unlock();
        }
    }

    /**
     * 测试当前用户的大模型连通性
     * 发送ping请求验证模型服务是否可达
     */
    public SettingsTestResult testModelSettings() {
        String userId = CurrentUserContext.getRequiredUserId();
        rwLock.readLock().lock();
        try {
            return doTestModel(settingsResolver.resolveModel(userId));
        } finally {
            rwLock.readLock().unlock();
        }
    }

    /**
     * 获取当前用户的语音识别配置
     */
    public AsrConfigDTO getAsrConfig() {
        String userId = CurrentUserContext.getRequiredUserId();
        rwLock.readLock().lock();
        try {
            return aiSettingsMapper.toAsrConfigDTO(settingsResolver.resolveAsr(userId));
        } finally {
            rwLock.readLock().unlock();
        }
    }

    /**
     * 测试语音识别服务的WebSocket连通性
     * 尝试建立TCP连接到ASR服务的WebSocket地址
     */
    public SettingsTestResult testAsrConfig() {
        String userId = CurrentUserContext.getRequiredUserId();
        rwLock.readLock().lock();
        try {
            AsrConfigSnapshot config = settingsResolver.resolveAsr(userId);
            try (Socket socket = new Socket()) {
                URI wsUri = URI.create(config.url());
                String host = wsUri.getHost();
                // 根据协议确定端口，wss默认443，ws默认80
                int port = wsUri.getPort() > 0 ? wsUri.getPort() : ("wss".equals(wsUri.getScheme()) ? 443 : 80);
                socket.connect(new InetSocketAddress(host, port), 5000);
                return SettingsTestResult.builder()
                        .success(true)
                        .message("语音识别WebSocket连接成功: " + host)
                        .model(config.model())
                        .build();
            } catch (Exception e) {
                return SettingsTestResult.builder()
                        .success(false)
                        .message("语音识别连接失败: " + e.getMessage())
                        .model(config.model())
                        .build();
            }
        } finally {
            rwLock.readLock().unlock();
        }
    }

    /**
     * 获取当前用户的语音合成配置
     */
    public TtsConfigDTO getTtsConfig() {
        String userId = CurrentUserContext.getRequiredUserId();
        rwLock.readLock().lock();
        try {
            return aiSettingsMapper.toTtsConfigDTO(settingsResolver.resolveTts(userId));
        } finally {
            rwLock.readLock().unlock();
        }
    }

    /**
     * 更新当前用户的大模型配置
     * 包含本地/云端模型切换逻辑和Embedding配置同步
     */
    @Transactional
    public void updateModelSettings(ModelSettingsRequest request) {
        String userId = CurrentUserContext.getRequiredUserId();
        rwLock.writeLock().lock();
        try {
            UserAiSettingsEntity entity = getOrCreateUserSettings(userId);
            // 切换本地模型前保留云端配置，用于后续切换回来时恢复
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
            // 通过Mapper统一处理数值类型字段的更新（temperature等）
            aiSettingsMapper.patchModelSettings(request, entity);
            if (request.embeddingDimensions() != null) {
                entity.setEmbeddingDimensions(resolveEmbeddingDimensions(request.embeddingDimensions()));
            }
            if (request.cloudEmbeddingDimensions() != null) {
                entity.setCloudEmbeddingDimensions(resolveEmbeddingDimensions(request.cloudEmbeddingDimensions()));
            }
            if (request.apiKey() != null) {
                String apiKey = trimOrNull(request.apiKey());
                if (apiKey != null) {
                    entity.setModelApiKey(apiKey);
                } else if (isLocalBaseUrl(entity.getModelBaseUrl())) {
                    // 本地模型使用固定密钥
                    entity.setModelApiKey("ollama");
                }
            }

            // 恢复之前保留的云端API密钥
            restoreCloudApiKeyIfNeeded(entity, request);
            // 同步云端Embedding配置
            syncCloudEmbeddingConfig(entity);
            validateModelConfig(entity);
            validateCloudApiKey(entity);
            validateCloudEmbeddingConfig(entity);
            settingsRepository.save(entity);
            // 事务提交后重载AI客户端，使新配置生效
            reloadAiClientsAfterCommit(userId);
            log.info("用户AI模型配置已更新: userId={}", userId);
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    /**
     * 更新当前用户的语音识别配置
     */
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
            if (request.turnDetectionType() != null) {
                entity.setAsrTurnDetectionType(requireNonBlank(request.turnDetectionType(), "turnDetectionType"));
            }
            // 通过Mapper统一处理数值类型字段的更新
            aiSettingsMapper.patchAsrConfig(request, entity);

            settingsRepository.save(entity);
            log.info("用户语音识别配置已更新: userId={}", userId);
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    /**
     * 更新当前用户的语音合成配置
     */
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
            if (request.mode() != null) {
                entity.setTtsMode(requireNonBlank(request.mode(), "mode"));
            }
            if (request.languageType() != null) {
                entity.setTtsLanguageType(requireNonBlank(request.languageType(), "languageType"));
            }
            // 通过Mapper统一处理数值类型字段的更新
            aiSettingsMapper.patchTtsConfig(request, entity);

            settingsRepository.save(entity);
            log.info("用户语音合成配置已更新: userId={}", userId);
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    /**
     * 获取或创建用户的AI配置实体
     */
    private UserAiSettingsEntity getOrCreateUserSettings(String userId) {
        return settingsRepository.findByUserId(userId)
                .orElseGet(() -> settingsResolver.buildDefaultEntity(userId));
    }

    /**
     * 切换本地模型前保留当前云端配置
     * 用于用户从云端模型切换到本地模型时保留云端密钥等配置
     */
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

    /**
     * 从云端切换回本地时，恢复之前保留的云端API密钥
     */
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

        // 尝试使用之前保留的云端密钥
        String preservedApiKey = trimOrNull(ConfigPlaceholderResolver.resolve(entity.getCloudEmbeddingApiKey()));
        if (preservedApiKey != null
                && !preservedApiKey.contains("${")
                && !isInvalidRemoteApiKey(entity.getModelBaseUrl(), preservedApiKey)) {
            entity.setModelApiKey(preservedApiKey);
        }
    }

    /**
     * 同步云端Embedding配置
     * 本地模型时使用默认云端配置，云端模型时与大模型配置保持一致
     */
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

    /**
     * 在事务提交后重载AI客户端
     * 确保新配置在事务成功提交后才生效
     */
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

    /**
     * 校验大模型必填配置
     */
    private void validateModelConfig(UserAiSettingsEntity entity) {
        if (isMissingResolvedValue(entity.getModelBaseUrl())
                || isMissingResolvedValue(entity.getChatModel())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "AI模型必填配置不能为空");
        }
    }

    /**
     * 校验云端模型的API密钥
     */
    private void validateCloudApiKey(UserAiSettingsEntity entity) {
        if (!isLocalBaseUrl(entity.getModelBaseUrl())
                && (isMissingResolvedValue(entity.getModelApiKey())
                || isInvalidRemoteApiKey(entity.getModelBaseUrl(), entity.getModelApiKey()))) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "云端模型API密钥不能为空");
        }
    }

    /**
     * 校验云端Embedding配置完整性
     */
    private void validateCloudEmbeddingConfig(UserAiSettingsEntity entity) {
        if (isMissingResolvedValue(entity.getCloudEmbeddingBaseUrl())
                || isLocalBaseUrl(entity.getCloudEmbeddingBaseUrl())
                || isInvalidRemoteApiKey(entity.getCloudEmbeddingBaseUrl(), entity.getCloudEmbeddingApiKey())
                || isMissingResolvedValue(entity.getCloudEmbeddingApiKey())
                || isMissingResolvedValue(entity.getCloudEmbeddingModel())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "云端Embedding配置不能为空");
        }
    }

    /**
     * 执行大模型连通性测试
     * 发送ping请求到模型的chat/completions端点
     */
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
                        .message("AI模型关键配置不能为空")
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

            // 发送ping消息，max_tokens=1减少消耗
            Map<String, Object> requestBody = Map.of(
                    "model", chatModel,
                    "messages", List.of(Map.of("role", "user", "content", "ping")),
                    "max_tokens", 1
            );
            String lastFailureMessage = "未知错误";
            // 尝试多个可能的端点路径
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

    /**
     * 构建连通性测试的候选URL列表
     * 尝试带/v1和不带/v1的两种路径
     */
    private List<String> buildConnectivityTestUrls(String baseUrl) {
        String normalized = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        Set<String> targetUrls = new LinkedHashSet<>();
        targetUrls.add(normalized + "/chat/completions");
        if (!normalized.endsWith("/v1")) {
            targetUrls.add(normalized + "/v1/chat/completions");
        }
        return new ArrayList<>(targetUrls);
    }

    /**
     * 校验字符串非空，为空时抛出异常
     */
    private String requireNonBlank(String value, String fieldName) {
        String trimmed = trimOrNull(value);
        if (trimmed == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, fieldName + " 不能为空");
        }
        return trimmed;
    }

    /**
     * 判断解析后的值是否缺失（null或仍包含未解析的占位符）
     */
    private boolean isMissingResolvedValue(String value) {
        String resolved = ConfigPlaceholderResolver.resolve(value);
        return trimOrNull(resolved) == null || resolved.contains("${");
    }

    /**
     * 标准化API密钥，本地模型自动使用"ollama"
     */
    private String normalizeApiKey(String baseUrl, String apiKey) {
        String normalized = trimOrNull(apiKey);
        if (normalized == null && isLocalBaseUrl(baseUrl)) {
            return "ollama";
        }
        return normalized;
    }

    /**
     * 判断云端模型的API密钥是否无效（本地密钥用于云端）
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
        if (configuredDimensions != null && configuredDimensions > 0) {
            return configuredDimensions;
        }
        return aiProperties.getEmbeddingDimensions();
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
}