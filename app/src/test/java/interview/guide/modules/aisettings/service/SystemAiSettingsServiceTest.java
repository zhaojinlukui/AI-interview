package interview.guide.modules.aisettings.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import interview.guide.common.exception.BusinessException;
import interview.guide.modules.aisettings.dto.SystemAiParametersDTO;
import interview.guide.modules.aisettings.dto.SystemAiParametersDTO.InterviewParametersDTO;
import interview.guide.modules.aisettings.dto.SystemAiParametersDTO.RagSearchParametersDTO;
import interview.guide.modules.aisettings.dto.SystemAiParametersDTO.ResumeWeightsDTO;
import interview.guide.modules.aisettings.model.SystemAiSettingsEntity;
import interview.guide.modules.aisettings.repository.SystemAiSettingsRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("全局 AI 参数服务")
class SystemAiSettingsServiceTest {

  @Mock
  private SystemAiSettingsRepository settingsRepository;

  private SystemAiSettingsService service;

  @BeforeEach
  void setUp() {
    SystemAiSettingsResolver resolver = new SystemAiSettingsResolver(settingsRepository);
    service = new SystemAiSettingsService(settingsRepository, resolver);
  }

  @Nested
  @DisplayName("默认值")
  class Defaults {

    @Test
    @DisplayName("数据库缺失记录时返回内置默认参数")
    void returnsDefaultsWhenRecordIsMissing() {
      when(settingsRepository.findById(SystemAiSettingsEntity.SINGLETON_ID))
          .thenReturn(Optional.empty());

      SystemAiParametersDTO parameters = service.getParameters();

      assertThat(parameters.resumeWeights())
          .isEqualTo(new ResumeWeightsDTO(40, 20, 15, 15, 10));
      assertThat(parameters.interview())
          .isEqualTo(new InterviewParametersDTO(60, 40, 0.2, 0.2, 0.2, 0.2));
      assertThat(parameters.ragSearch())
          .isEqualTo(new RagSearchParametersDTO(20, 12, 8, 0.18, 0.28, 0.28));
    }
  }

  @Nested
  @DisplayName("保存")
  class Save {

    @Test
    @DisplayName("首次保存时使用 id=1 创建全局配置")
    void createsSingletonRecordOnFirstSave() {
      when(settingsRepository.findById(SystemAiSettingsEntity.SINGLETON_ID))
          .thenReturn(Optional.empty());
      SystemAiParametersDTO request = new SystemAiParametersDTO(
          new ResumeWeightsDTO(35, 25, 15, 15, 10),
          new InterviewParametersDTO(70, 30, 0.3, 0.4, 0.5, 0.6),
          new RagSearchParametersDTO(10, 11, 12, 0.1, 0.2, 0.3)
      );

      service.updateParameters(request);

      ArgumentCaptor<SystemAiSettingsEntity> captor =
          ArgumentCaptor.forClass(SystemAiSettingsEntity.class);
      verify(settingsRepository).save(captor.capture());
      SystemAiSettingsEntity saved = captor.getValue();
      assertThat(saved.getId()).isEqualTo(SystemAiSettingsEntity.SINGLETON_ID);
      assertThat(saved.getResumeProjectWeight()).isEqualTo(35);
      assertThat(saved.getInterviewResumeQuestionRatio()).isEqualTo(70);
      assertThat(saved.getInterviewCommentTemperature()).isEqualTo(0.6);
      assertThat(saved.getRagTopkLong()).isEqualTo(12);
      assertThat(saved.getRagMinScoreLong()).isEqualTo(0.3);
    }
  }

  @Nested
  @DisplayName("校验")
  class Validation {

    @Test
    @DisplayName("简历五维权重合计必须为 100")
    void rejectsInvalidResumeWeightTotal() {
      assertInvalid(validRequest(new ResumeWeightsDTO(40, 20, 15, 15, 9), null, null));
    }

    @Test
    @DisplayName("面试简历题和方向题比例合计必须为 100")
    void rejectsInvalidInterviewRatioTotal() {
      assertInvalid(validRequest(null, new InterviewParametersDTO(60, 30, 0.2, 0.2, 0.2, 0.2), null));
    }

    @Test
    @DisplayName("温度范围必须在 0.0 到 2.0")
    void rejectsInvalidTemperature() {
      assertInvalid(validRequest(null, new InterviewParametersDTO(60, 40, 2.1, 0.2, 0.2, 0.2), null));
    }

    @Test
    @DisplayName("RAG topK 范围必须在 1 到 50")
    void rejectsInvalidTopK() {
      assertInvalid(validRequest(null, null, new RagSearchParametersDTO(0, 12, 8, 0.18, 0.28, 0.28)));
    }

    @Test
    @DisplayName("RAG 最小相似度范围必须在 0.0 到 1.0")
    void rejectsInvalidMinScore() {
      assertInvalid(validRequest(null, null, new RagSearchParametersDTO(20, 12, 8, 0.18, 1.1, 0.28)));
    }

    private void assertInvalid(SystemAiParametersDTO request) {
      assertThatThrownBy(() -> service.updateParameters(request))
          .isInstanceOf(BusinessException.class);
      verify(settingsRepository, never()).save(any());
    }
  }

  private SystemAiParametersDTO validRequest(
      ResumeWeightsDTO resumeWeights,
      InterviewParametersDTO interview,
      RagSearchParametersDTO ragSearch
  ) {
    return new SystemAiParametersDTO(
        resumeWeights == null ? new ResumeWeightsDTO(40, 20, 15, 15, 10) : resumeWeights,
        interview == null ? new InterviewParametersDTO(60, 40, 0.2, 0.2, 0.2, 0.2) : interview,
        ragSearch == null ? new RagSearchParametersDTO(20, 12, 8, 0.18, 0.28, 0.28) : ragSearch
    );
  }
}
