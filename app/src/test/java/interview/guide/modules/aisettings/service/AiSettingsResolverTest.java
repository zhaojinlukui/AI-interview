package interview.guide.modules.aisettings.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import interview.guide.common.config.AiProperties;
import interview.guide.modules.aisettings.model.UserAiSettingsEntity;
import interview.guide.modules.aisettings.repository.UserAiSettingsRepository;
import interview.guide.modules.aisettings.service.AiSettingsResolver.ModelConfigSnapshot;
import interview.guide.modules.voiceinterview.config.VoiceInterviewProperties;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("用户 AI 设置解析器")
class AiSettingsResolverTest {

  private static final String LOCAL_BASE_URL = "http://localhost:11434/v1";
  private static final String CLOUD_BASE_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1";
  private static final String CLOUD_API_KEY = "sk-cloud";
  private static final String CLOUD_EMBEDDING_MODEL = "text-embedding-v4";

  @Mock
  private UserAiSettingsRepository settingsRepository;

  private AiProperties aiProperties;
  private AiSettingsResolver resolver;

  @BeforeEach
  void setUp() {
    aiProperties = new AiProperties();
    aiProperties.setEmbeddingDimensions(1024);
    aiProperties.getModel().setBaseUrl(LOCAL_BASE_URL);
    aiProperties.getModel().setApiKey("ollama");
    aiProperties.getModel().setChatModel("qwen2.5:1.5b");
    aiProperties.getModel().setEmbeddingModel("mxbai-embed-large");
    aiProperties.getModel().setEmbeddingDimensions(1024);
    aiProperties.getModel().setTemperature(0.2);
    aiProperties.getCloudEmbedding().setBaseUrl(CLOUD_BASE_URL);
    aiProperties.getCloudEmbedding().setApiKey(CLOUD_API_KEY);
    aiProperties.getCloudEmbedding().setModel(CLOUD_EMBEDDING_MODEL);
    aiProperties.getCloudEmbedding().setDimensions(1024);
    resolver = new AiSettingsResolver(
        aiProperties,
        new VoiceInterviewProperties(),
        settingsRepository
    );
  }

  @Nested
  @DisplayName("Embedding 配置")
  class EmbeddingConfig {

    @Test
    @DisplayName("默认聊天模型为本地时仍返回云端 Embedding")
    void returnsCloudEmbeddingDefaultsWhenChatDefaultIsLocal() {
      when(settingsRepository.findByUserId("user1")).thenReturn(Optional.empty());

      ModelConfigSnapshot config = resolver.resolveEmbeddingModel("user1");

      assertThat(config.baseUrl()).isEqualTo(CLOUD_BASE_URL);
      assertThat(config.apiKey()).isEqualTo(CLOUD_API_KEY);
      assertThat(config.embeddingModel()).isEqualTo(CLOUD_EMBEDDING_MODEL);
      assertThat(config.embeddingDimensions()).isEqualTo(1024);
    }

    @Test
    @DisplayName("用户切换本地模型时使用保留的云端 Embedding")
    void returnsCloudEmbeddingFieldsWhenUserModelIsLocal() {
      UserAiSettingsEntity entity = UserAiSettingsEntity.builder()
          .userId("user1")
          .modelBaseUrl(LOCAL_BASE_URL)
          .modelApiKey("ollama")
          .chatModel("qwen2.5:1.5b")
          .cloudEmbeddingBaseUrl("https://example.com/v1")
          .cloudEmbeddingApiKey("sk-preserved")
          .cloudEmbeddingModel("embedding-preserved")
          .cloudEmbeddingDimensions(1536)
          .build();
      when(settingsRepository.findByUserId("user1")).thenReturn(Optional.of(entity));

      ModelConfigSnapshot config = resolver.resolveEmbeddingModel("user1");

      assertThat(config.baseUrl()).isEqualTo("https://example.com/v1");
      assertThat(config.apiKey()).isEqualTo("sk-preserved");
      assertThat(config.embeddingModel()).isEqualTo("embedding-preserved");
      assertThat(config.embeddingDimensions()).isEqualTo(1536);
    }

    @Test
    @DisplayName("local model cloud embedding falls back when ollama sentinel is saved")
    void fallsBackToCloudEmbeddingDefaultWhenLocalSentinelWasSavedAsCloudKey() {
      UserAiSettingsEntity entity = UserAiSettingsEntity.builder()
          .userId("user1")
          .modelBaseUrl(LOCAL_BASE_URL)
          .modelApiKey("ollama")
          .chatModel("qwen2.5:1.5b")
          .cloudEmbeddingBaseUrl(CLOUD_BASE_URL)
          .cloudEmbeddingApiKey("ollama")
          .cloudEmbeddingModel(CLOUD_EMBEDDING_MODEL)
          .cloudEmbeddingDimensions(1024)
          .build();
      when(settingsRepository.findByUserId("user1")).thenReturn(Optional.of(entity));

      ModelConfigSnapshot config = resolver.resolveEmbeddingModel("user1");

      assertThat(config.baseUrl()).isEqualTo(CLOUD_BASE_URL);
      assertThat(config.apiKey()).isEqualTo(CLOUD_API_KEY);
      assertThat(config.embeddingModel()).isEqualTo(CLOUD_EMBEDDING_MODEL);
      assertThat(config.embeddingDimensions()).isEqualTo(1024);
    }

    @Test
    @DisplayName("云端模型缺少向量字段时使用云端 Embedding 默认值")
    void fillsCloudEmbeddingDefaultsForCloudChatModel() {
      UserAiSettingsEntity entity = UserAiSettingsEntity.builder()
          .userId("user1")
          .modelBaseUrl(CLOUD_BASE_URL)
          .modelApiKey(CLOUD_API_KEY)
          .chatModel("qwen3.5-flash")
          .build();
      when(settingsRepository.findByUserId("user1")).thenReturn(Optional.of(entity));

      ModelConfigSnapshot config = resolver.resolveEmbeddingModel("user1");

      assertThat(config.baseUrl()).isEqualTo(CLOUD_BASE_URL);
      assertThat(config.apiKey()).isEqualTo(CLOUD_API_KEY);
      assertThat(config.embeddingModel()).isEqualTo(CLOUD_EMBEDDING_MODEL);
      assertThat(config.embeddingDimensions()).isEqualTo(1024);
    }
  }
}
