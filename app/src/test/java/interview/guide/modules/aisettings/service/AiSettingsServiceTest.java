package interview.guide.modules.aisettings.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import interview.guide.common.ai.AiClientFactory;
import interview.guide.common.config.AiProperties;
import interview.guide.infrastructure.mapper.AiSettingsMapper;
import interview.guide.modules.aisettings.dto.ModelSettingsRequest;
import interview.guide.modules.aisettings.model.UserAiSettingsEntity;
import interview.guide.modules.aisettings.repository.UserAiSettingsRepository;
import interview.guide.modules.aisettings.service.AiSettingsResolver.ModelConfigSnapshot;
import interview.guide.modules.user.model.UserRole;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mapstruct.factory.Mappers;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@ExtendWith(MockitoExtension.class)
@DisplayName("User AI settings service")
class AiSettingsServiceTest {

  private static final String USER_ID = "user1";
  private static final String LOCAL_BASE_URL = "http://localhost:11434/v1";
  private static final String CLOUD_BASE_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1";
  private static final String CLOUD_API_KEY = "sk-cloud-current";
  private static final String PRESERVED_CLOUD_API_KEY = "sk-cloud-preserved";
  private static final String CLOUD_EMBEDDING_MODEL = "text-embedding-v4";

  @Mock
  private AiClientFactory aiClientFactory;

  @Mock
  private AiSettingsResolver settingsResolver;

  @Mock
  private UserAiSettingsRepository settingsRepository;

  private AiSettingsService service;

  @BeforeEach
  void setUp() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setAttribute("userId", USER_ID);
    request.setAttribute("username", "tester");
    request.setAttribute("userRole", UserRole.USER.name());
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

    when(settingsResolver.resolveEmbeddingModel(null)).thenReturn(defaultCloudEmbedding());
    when(settingsRepository.save(any(UserAiSettingsEntity.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    service = new AiSettingsService(
        new AiProperties(),
        aiClientFactory,
        settingsResolver,
        settingsRepository,
        Mappers.getMapper(AiSettingsMapper.class)
    );
  }

  @AfterEach
  void tearDown() {
    RequestContextHolder.resetRequestAttributes();
  }

  @Nested
  @DisplayName("Model switching")
  class ModelSwitching {

    @Test
    @DisplayName("preserves cloud key when switching to local model")
    void preservesCloudApiKeyWhenSwitchingToLocalModel() {
      UserAiSettingsEntity entity = cloudEntity();
      entity.setCloudEmbeddingApiKey("sk-old-cloud");
      when(settingsRepository.findByUserId(USER_ID)).thenReturn(Optional.of(entity));

      service.updateModelSettings(new ModelSettingsRequest(
          LOCAL_BASE_URL,
          "ollama",
          "qwen2.5:1.5b",
          null,
          null,
          CLOUD_BASE_URL,
          null,
          CLOUD_EMBEDDING_MODEL,
          1024,
          null
      ));

      UserAiSettingsEntity saved = captureSavedEntity();
      assertThat(saved.getModelBaseUrl()).isEqualTo(LOCAL_BASE_URL);
      assertThat(saved.getModelApiKey()).isEqualTo("ollama");
      assertThat(saved.getCloudEmbeddingBaseUrl()).isEqualTo(CLOUD_BASE_URL);
      assertThat(saved.getCloudEmbeddingApiKey()).isEqualTo(CLOUD_API_KEY);
    }

    @Test
    @DisplayName("restores preserved cloud key when switching back to cloud model")
    void restoresPreservedCloudApiKeyWhenSwitchingBackToCloudModel() {
      UserAiSettingsEntity entity = localEntityWithPreservedCloudKey();
      when(settingsRepository.findByUserId(USER_ID)).thenReturn(Optional.of(entity));

      service.updateModelSettings(new ModelSettingsRequest(
          CLOUD_BASE_URL,
          null,
          "qwen-turbo",
          CLOUD_EMBEDDING_MODEL,
          1024,
          null,
          null,
          null,
          null,
          null
      ));

      UserAiSettingsEntity saved = captureSavedEntity();
      assertThat(saved.getModelBaseUrl()).isEqualTo(CLOUD_BASE_URL);
      assertThat(saved.getModelApiKey()).isEqualTo(PRESERVED_CLOUD_API_KEY);
      assertThat(saved.getCloudEmbeddingApiKey()).isEqualTo(PRESERVED_CLOUD_API_KEY);
    }
  }

  private UserAiSettingsEntity captureSavedEntity() {
    ArgumentCaptor<UserAiSettingsEntity> captor =
        ArgumentCaptor.forClass(UserAiSettingsEntity.class);
    verify(settingsRepository).save(captor.capture());
    return captor.getValue();
  }

  private UserAiSettingsEntity cloudEntity() {
    return UserAiSettingsEntity.builder()
        .userId(USER_ID)
        .modelBaseUrl(CLOUD_BASE_URL)
        .modelApiKey(CLOUD_API_KEY)
        .chatModel("qwen-turbo")
        .embeddingModel(CLOUD_EMBEDDING_MODEL)
        .embeddingDimensions(1024)
        .cloudEmbeddingBaseUrl(CLOUD_BASE_URL)
        .cloudEmbeddingApiKey(CLOUD_API_KEY)
        .cloudEmbeddingModel(CLOUD_EMBEDDING_MODEL)
        .cloudEmbeddingDimensions(1024)
        .temperature(0.2)
        .build();
  }

  private UserAiSettingsEntity localEntityWithPreservedCloudKey() {
    return UserAiSettingsEntity.builder()
        .userId(USER_ID)
        .modelBaseUrl(LOCAL_BASE_URL)
        .modelApiKey("ollama")
        .chatModel("qwen2.5:1.5b")
        .cloudEmbeddingBaseUrl(CLOUD_BASE_URL)
        .cloudEmbeddingApiKey(PRESERVED_CLOUD_API_KEY)
        .cloudEmbeddingModel(CLOUD_EMBEDDING_MODEL)
        .cloudEmbeddingDimensions(1024)
        .temperature(0.2)
        .build();
  }

  private ModelConfigSnapshot defaultCloudEmbedding() {
    return new ModelConfigSnapshot(
        CLOUD_BASE_URL,
        CLOUD_API_KEY,
        "qwen-turbo",
        CLOUD_EMBEDDING_MODEL,
        1024,
        0.2
    );
  }
}

