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

@Component
@Slf4j
public class AiClientFactory {

  private final AiProperties properties;
  private final AiSettingsResolver settingsResolver;
  private final ToolCallingManager toolCallingManager;
  private final ObservationRegistry observationRegistry;
  private final ToolCallback interviewSkillsToolCallback;

  private final Map<String, ChatClient> clientCache = new ConcurrentHashMap<>();
  private final Map<String, EmbeddingModel> embeddingCache = new ConcurrentHashMap<>();

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

  public ChatClient getDefaultChatClient() {
    return getDefaultChatClient(resolveCurrentUserId());
  }

  public ChatClient getDefaultChatClient(String userId) {
    return clientCache.computeIfAbsent(
        cacheKey("default", userId),
        key -> createDefaultChatClient(userId)
    );
  }

  public ChatClient getPlainChatClient() {
    return getPlainChatClient(resolveCurrentUserId());
  }

  public ChatClient getPlainChatClient(String userId) {
    return clientCache.computeIfAbsent(
        cacheKey("plain", userId),
        key -> createPlainChatClient(userId)
    );
  }

  public ChatClient getVoiceChatClient() {
    return getVoiceChatClient(resolveCurrentUserId());
  }

  public ChatClient getVoiceChatClient(String userId) {
    return clientCache.computeIfAbsent(
        cacheKey("voice", userId),
        key -> createVoiceChatClient(userId)
    );
  }

  public EmbeddingModel getEmbeddingModel() {
    return getEmbeddingModel(resolveCurrentUserId());
  }

  public EmbeddingModel getEmbeddingModel(String userId) {
    return embeddingCache.computeIfAbsent(
        cacheKey("default", userId),
        key -> createEmbeddingModel(userId)
    );
  }

  public void reload() {
    int size = clientCache.size() + embeddingCache.size();
    clientCache.clear();
    embeddingCache.clear();
    log.info("AI client cache cleared: {} entries", size);
  }

  public void reload(String userId) {
    String suffix = ":" + normalizeUserId(userId);
    clientCache.keySet().removeIf(key -> key.endsWith(suffix));
    embeddingCache.keySet().removeIf(key -> key.endsWith(suffix));
    log.info("AI client cache cleared for userId={}", userId);
  }

  private ChatClient createDefaultChatClient(String userId) {
    ChatClient.Builder builder = ChatClient.builder(buildChatModel(userId));
    if (interviewSkillsToolCallback != null) {
      builder.defaultToolCallbacks(interviewSkillsToolCallback);
    }
    List<Advisor> advisors = buildDefaultAdvisors();
    if (!advisors.isEmpty()) {
      builder.defaultAdvisors(advisors.toArray(new Advisor[0]));
    }
    return builder.build();
  }

  private ChatClient createPlainChatClient(String userId) {
    ChatClient.Builder builder = ChatClient.builder(buildChatModel(userId));
    buildSafeGuardAdvisor().ifPresent(builder::defaultAdvisors);
    return builder.build();
  }

  private ChatClient createVoiceChatClient(String userId) {
    ChatClient.Builder builder = ChatClient.builder(buildChatModel(userId));
    buildSafeGuardAdvisor().ifPresent(builder::defaultAdvisors);
    return builder.build();
  }

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

  private List<Advisor> buildDefaultAdvisors() {
    AdvisorConfig config = properties.getAdvisors();
    if (config == null || !config.isEnabled()) {
      return List.of();
    }

    List<Advisor> advisors = new ArrayList<>();
    if (config.isToolCallEnabled()) {
      if (toolCallingManager != null) {
        advisors.add(buildToolCallAdvisor(
            config.isToolCallConversationHistoryEnabled(),
            config.isStreamToolCallResponses()
        ));
      } else {
        log.warn("ToolCallAdvisor skipped: ToolCallingManager unavailable");
      }
    }
    if (config.isMessageChatMemoryEnabled()) {
      int maxMessages = Math.max(20, config.getMessageChatMemoryMaxMessages());
      advisors.add(MessageChatMemoryAdvisor.builder(
          MessageWindowChatMemory.builder().maxMessages(maxMessages).build()
      ).build());
    }
    if (config.isSimpleLoggerEnabled()) {
      advisors.add(new SimpleLoggerAdvisor());
    }
    buildSafeGuardAdvisor().ifPresent(advisors::add);
    return advisors;
  }

  private ToolCallAdvisor buildToolCallAdvisor(
      boolean conversationHistoryEnabled,
      boolean streamToolCallResponses) {
    return ToolCallAdvisor.builder()
        .toolCallingManager(toolCallingManager)
        .conversationHistoryEnabled(conversationHistoryEnabled)
        .streamToolCallResponses(streamToolCallResponses)
        .build();
  }

  private Optional<SafeGuardAdvisor> buildSafeGuardAdvisor() {
    AdvisorConfig config = properties.getAdvisors();
    if (config == null || !config.isSafeguardEnabled()) {
      return Optional.empty();
    }
    return Optional.of(
        SafeGuardAdvisor.builder()
            .sensitiveWords(config.getSafeguardWords())
            .failureResponse("抱歉，我只能协助面试相关的任务。")
            .order(100)
            .build()
    );
  }

  private ModelConfigSnapshot loadConfigOrThrow(String userId) {
    ModelConfigSnapshot config = settingsResolver.resolveModel(userId);
    if (isMissing(config.baseUrl())
        || (!isLocalBaseUrl(config.baseUrl()) && isMissingRemoteApiKey(config.apiKey()))
        || isMissing(config.chatModel())) {
      throw new BusinessException(ErrorCode.PROVIDER_CONFIG_READ_FAILED, "AI 模型关键配置不能为空");
    }
    return config;
  }

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

  private Integer resolveEmbeddingDimensions(Integer configuredDimensions) {
    if (configuredDimensions != null && configuredDimensions > 0) {
      return configuredDimensions;
    }
    return properties.getEmbeddingDimensions();
  }

  private boolean isMissing(String value) {
    return value == null || value.isBlank() || value.contains("${");
  }

  private boolean isMissingRemoteApiKey(String apiKey) {
    return isMissing(apiKey) || "ollama".equalsIgnoreCase(apiKey.trim());
  }

  private String normalizeApiKey(String baseUrl, String apiKey) {
    if (isMissing(apiKey) && isLocalBaseUrl(baseUrl)) {
      return "ollama";
    }
    return apiKey;
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

  private String cacheKey(String type, String userId) {
    return type + ":" + normalizeUserId(userId);
  }

  private String resolveCurrentUserId() {
    String contextualUserId = AiSettingsUserContext.getUserIdOrNull();
    return contextualUserId != null ? contextualUserId : CurrentUserContext.getCurrentUserIdOrNull();
  }

  private String normalizeUserId(String userId) {
    return userId == null || userId.isBlank() ? "system" : userId.trim();
  }
}
