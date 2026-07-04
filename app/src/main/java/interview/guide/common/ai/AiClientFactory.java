package interview.guide.common.ai;

import interview.guide.common.auth.CurrentUserContext;
import interview.guide.common.config.AiProperties;
import interview.guide.common.config.AiProperties.AdvisorConfig;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.aisettings.service.AiSettingsResolver;
import interview.guide.modules.aisettings.service.AiSettingsResolver.ModelConfigSnapshot;
import io.micrometer.observation.ObservationRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SafeGuardAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.client.advisor.ToolCallAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.retry.RetryUtils;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * AI客户端工厂
 * 负责创建和管理各种AI客户端实例（对话模型、Embedding模型），
 * 支持用户级配置隔离和本地缓存，提供默认、纯对话和语音三种ChatClient，
 * 支持客户端缓存重载以响应配置热更新
 */
@Component
@Slf4j
public class AiClientFactory {

    private final AiProperties properties; // AI相关全局属性
    private final AiSettingsResolver settingsResolver; // 用户AI配置解析器
    private final ToolCallingManager toolCallingManager; // 工具调用管理器
    private final ObservationRegistry observationRegistry; // 观测注册器
    private final ToolCallback interviewSkillsToolCallback; // 面试技能工具回调

    private final Map<String, ChatClient> clientCache = new ConcurrentHashMap<>(); // ChatClient缓存
    private final Map<String, EmbeddingModel> embeddingCache = new ConcurrentHashMap<>(); // EmbeddingModel缓存

    @Autowired
    public AiClientFactory(
            AiProperties properties,
            AiSettingsResolver settingsResolver,
            @Autowired(required = false) ToolCallingManager toolCallingManager,
            @Autowired(required = false) ObservationRegistry observationRegistry,
            @Autowired(required = false)
            @Qualifier("interviewSkillsToolCallback") ToolCallback interviewSkillsToolCallback) {
        this.properties = properties;
        this.settingsResolver = settingsResolver;
        this.toolCallingManager = toolCallingManager;
        this.observationRegistry = observationRegistry;
        this.interviewSkillsToolCallback = interviewSkillsToolCallback;
    }

    /**
     * 获取默认ChatClient（含工具调用和对话记忆等Advisor）
     */
    public ChatClient getDefaultChatClient() {
        return getDefaultChatClient(resolveCurrentUserId());
    }

    public ChatClient getDefaultChatClient(String userId) {
        return clientCache.computeIfAbsent(
                cacheKey("default", userId),
                key -> createDefaultChatClient(userId)
        );
    }

    /**
     * 获取纯对话ChatClient（仅含安全守卫Advisor）
     */
    public ChatClient getPlainChatClient() {
        return getPlainChatClient(resolveCurrentUserId());
    }

    public ChatClient getPlainChatClient(String userId) {
        return clientCache.computeIfAbsent(
                cacheKey("plain", userId),
                key -> createPlainChatClient(userId)
        );
    }

    /**
     * 获取语音对话ChatClient（仅含安全守卫Advisor）
     */
    public ChatClient getVoiceChatClient() {
        return getVoiceChatClient(resolveCurrentUserId());
    }

    public ChatClient getVoiceChatClient(String userId) {
        return clientCache.computeIfAbsent(
                cacheKey("voice", userId),
                key -> createVoiceChatClient(userId)
        );
    }

    /**
     * 获取Embedding模型
     */
    public EmbeddingModel getEmbeddingModel() {
        return getEmbeddingModel(resolveCurrentUserId());
    }

    public EmbeddingModel getEmbeddingModel(String userId) {
        return embeddingCache.computeIfAbsent(
                cacheKey("default", userId),
                key -> createEmbeddingModel(userId)
        );
    }

    /**
     * 重载指定用户的AI客户端缓存
     */
    public void reload(String userId) {
        String suffix = ":" + normalizeUserId(userId);
        clientCache.keySet().removeIf(key -> key.endsWith(suffix));
        embeddingCache.keySet().removeIf(key -> key.endsWith(suffix));
        log.info("AI客户端缓存已清空，userId={}", userId);
    }

    /**
     * 创建默认ChatClient
     * 包含面试技能工具回调、工具调用Advisor、对话记忆Advisor、日志Advisor和安全守卫
     */
    private ChatClient createDefaultChatClient(String userId) {
        ChatClient.Builder builder = ChatClient.builder(buildChatModel(userId));
        // 注册面试技能工具回调
        if (interviewSkillsToolCallback != null) {
            builder.defaultToolCallbacks(interviewSkillsToolCallback);
        }
        List<Advisor> advisors = buildDefaultAdvisors();
        if (!advisors.isEmpty()) {
            builder.defaultAdvisors(advisors.toArray(new Advisor[0]));
        }
        return builder.build();
    }

    /**
     * 创建纯对话ChatClient（仅含安全守卫）
     */
    private ChatClient createPlainChatClient(String userId) {
        ChatClient.Builder builder = ChatClient.builder(buildChatModel(userId));
        buildSafeGuardAdvisor().ifPresent(builder::defaultAdvisors);
        return builder.build();
    }

    /**
     * 创建语音对话ChatClient（仅含安全守卫）
     */
    private ChatClient createVoiceChatClient(String userId) {
        ChatClient.Builder builder = ChatClient.builder(buildChatModel(userId));
        buildSafeGuardAdvisor().ifPresent(builder::defaultAdvisors);
        return builder.build();
    }

    /**
     * 构建OpenAI对话模型
     * 根据用户配置创建OpenAiChatModel实例
     */
    private OpenAiChatModel buildChatModel(String userId) {
        ModelConfigSnapshot config = loadConfigOrThrow(userId);
        OpenAiApi openAiApi = ApiPathResolver.buildOpenAiApi(
                config.baseUrl(),
                normalizeApiKey(config.baseUrl(), config.apiKey())
        );
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(config.chatModel())
                .temperature(config.temperature() != null ? config.temperature() : 0.2)
                .build();

        return new OpenAiChatModel(
                openAiApi,
                options,
                toolCallingManager,
                RetryUtils.DEFAULT_RETRY_TEMPLATE,
                observationRegistry != null ? observationRegistry : ObservationRegistry.NOOP
        );
    }

    /**
     * 创建Embedding模型
     * 云端模型时设置维度，本地模型时不设置
     */
    private EmbeddingModel createEmbeddingModel(String userId) {
        ModelConfigSnapshot config = loadEmbeddingConfigOrThrow(userId);
        if (isMissing(config.embeddingModel())) {
            throw new BusinessException(
                    ErrorCode.PROVIDER_CONFIG_READ_FAILED,
                    "未配置可用的 Embedding 模型"
            );
        }

        OpenAiApi openAiApi = ApiPathResolver.buildOpenAiApi(
                config.baseUrl(),
                normalizeApiKey(config.baseUrl(), config.apiKey())
        );
        OpenAiEmbeddingOptions.Builder optionsBuilder = OpenAiEmbeddingOptions.builder()
                .model(config.embeddingModel());
        // 云端模型设置维度
        if (!isLocalBaseUrl(config.baseUrl())) {
            optionsBuilder.dimensions(resolveEmbeddingDimensions(config.embeddingDimensions()));
        }
        OpenAiEmbeddingOptions options = optionsBuilder.build();

        return new OpenAiEmbeddingModel(
                openAiApi,
                MetadataMode.EMBED,
                options,
                RetryUtils.DEFAULT_RETRY_TEMPLATE,
                observationRegistry != null ? observationRegistry : ObservationRegistry.NOOP
        );
    }

    /**
     * 构建默认Advisor列表
     * 根据配置决定是否启用工具调用、对话记忆、日志和安全守卫
     */
    private List<Advisor> buildDefaultAdvisors() {
        AdvisorConfig config = properties.getAdvisors();
        if (config == null || !config.isEnabled()) {
            return List.of();
        }

        List<Advisor> advisors = new ArrayList<>();
        // 工具调用Advisor
        if (config.isToolCallEnabled()) {
            if (toolCallingManager != null) {
                advisors.add(buildToolCallAdvisor(
                        config.isToolCallConversationHistoryEnabled(),
                        config.isStreamToolCallResponses()
                ));
            } else {
                log.warn("ToolCallAdvisor已跳过：ToolCallingManager不可用");
            }
        }
        // 对话记忆Advisor（滑动窗口，最大消息数可配置）
        if (config.isMessageChatMemoryEnabled()) {
            int maxMessages = Math.max(20, config.getMessageChatMemoryMaxMessages());
            advisors.add(MessageChatMemoryAdvisor.builder(
                    MessageWindowChatMemory.builder().maxMessages(maxMessages).build()
            ).build());
        }
        // 日志Advisor
        if (config.isSimpleLoggerEnabled()) {
            advisors.add(new SimpleLoggerAdvisor());
        }
        // 安全守卫Advisor
        buildSafeGuardAdvisor().ifPresent(advisors::add);
        return advisors;
    }

    /**
     * 构建工具调用Advisor
     */
    private ToolCallAdvisor buildToolCallAdvisor(
            boolean conversationHistoryEnabled,
            boolean streamToolCallResponses) {
        return ToolCallAdvisor.builder()
                .toolCallingManager(toolCallingManager)
                .conversationHistoryEnabled(conversationHistoryEnabled)
                .streamToolCallResponses(streamToolCallResponses)
                .build();
    }

    /**
     * 构建安全守卫Advisor
     * 包含敏感词过滤和统一拒绝回复
     */
    private Optional<SafeGuardAdvisor> buildSafeGuardAdvisor() {
        AdvisorConfig config = properties.getAdvisors();
        if (config == null || !config.isSafeguardEnabled()) {
            return Optional.empty();
        }
        return Optional.of(
                SafeGuardAdvisor.builder()
                        .sensitiveWords(config.getSafeguardWords())
                        .failureResponse("抱歉，我只能协助面试相关的任务。")
                        .order(100) // 最后执行的安全检查
                        .build()
        );
    }

    /**
     * 加载对话模型配置，关键字段缺失时抛出异常
     */
    private ModelConfigSnapshot loadConfigOrThrow(String userId) {
        ModelConfigSnapshot config = settingsResolver.resolveModel(userId);
        if (isMissing(config.baseUrl())
                || (!isLocalBaseUrl(config.baseUrl()) && isMissingRemoteApiKey(config.apiKey()))
                || isMissing(config.chatModel())) {
            throw new BusinessException(ErrorCode.PROVIDER_CONFIG_READ_FAILED, "AI 模型关键配置不能为空");
        }
        return config;
    }

    /**
     * 加载Embedding模型配置，仅支持云端模型
     */
    private ModelConfigSnapshot loadEmbeddingConfigOrThrow(String userId) {
        ModelConfigSnapshot config = settingsResolver.resolveEmbeddingModel(userId);
        if (isMissing(config.baseUrl())
                || isLocalBaseUrl(config.baseUrl())
                || isMissingRemoteApiKey(config.apiKey())
                || isMissing(config.embeddingModel())) {
            throw new BusinessException(ErrorCode.PROVIDER_CONFIG_READ_FAILED, "云端 Embedding 配置不能为空");
        }
        return config;
    }

    /**
     * 解析Embedding维度，未配置时使用全局默认值
     */
    private Integer resolveEmbeddingDimensions(Integer configuredDimensions) {
        if (configuredDimensions != null && configuredDimensions > 0) {
            return configuredDimensions;
        }
        return properties.getEmbeddingDimensions();
    }

    /**
     * 判断字符串是否缺失（null、空或含未解析占位符）
     */
    private boolean isMissing(String value) {
        return value == null || value.isBlank() || value.contains("${");
    }

    /**
     * 判断云端API密钥是否缺失或无效
     */
    private boolean isMissingRemoteApiKey(String apiKey) {
        return isMissing(apiKey) || "ollama".equalsIgnoreCase(apiKey.trim());
    }

    /**
     * 标准化API密钥，本地模型自动使用"ollama"
     */
    private String normalizeApiKey(String baseUrl, String apiKey) {
        if (isMissing(apiKey) && isLocalBaseUrl(baseUrl)) {
            return "ollama";
        }
        return apiKey;
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
     * 构建缓存键
     */
    private String cacheKey(String type, String userId) {
        return type + ":" + normalizeUserId(userId);
    }

    /**
     * 解析当前用户ID
     * 优先使用异步上下文中的用户ID，其次使用当前请求上下文
     */
    private String resolveCurrentUserId() {
        String contextualUserId = AiSettingsUserContext.getUserIdOrNull();
        return contextualUserId != null ? contextualUserId : CurrentUserContext.getCurrentUserIdOrNull();
    }

    /**
     * 标准化用户ID，空值统一为"system"
     */
    private String normalizeUserId(String userId) {
        return userId == null || userId.isBlank() ? "system" : userId.trim();
    }
}