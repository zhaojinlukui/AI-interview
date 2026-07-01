package interview.guide.modules.voiceinterview.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.when;

import interview.guide.common.ai.AiClientFactory;
import interview.guide.common.ai.PromptSanitizer;
import interview.guide.common.config.AiProperties;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.resume.repository.ResumeRepository;
import interview.guide.modules.voiceinterview.config.VoiceInterviewProperties;
import interview.guide.modules.voiceinterview.model.VoiceInterviewSessionEntity;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("Voice LLM service")
class VoiceLlmServiceTest {

  @Mock
  private AiClientFactory aiClientFactory;

  @Mock
  private VoiceInterviewPromptService promptService;

  @Mock
  private ResumeRepository resumeRepository;

  private VoiceLlmService service;

  @BeforeEach
  void setUp() {
    service = new VoiceLlmService(
        aiClientFactory,
        promptService,
        resumeRepository,
        new VoiceInterviewProperties(),
        new PromptSanitizer(new AiProperties())
    );
  }

  @Test
  @DisplayName("配置异常应原样抛出而不是变成面试官回复")
  void chatShouldRethrowBusinessException() {
    VoiceInterviewSessionEntity session = buildSession();
    when(promptService.generateSystemPromptWithContext("java-backend", null))
        .thenReturn("system prompt");
    when(aiClientFactory.getVoiceChatClient("u1")).thenThrow(
        new BusinessException(ErrorCode.PROVIDER_CONFIG_READ_FAILED, "AI 模型关键配置不能为空")
    );

    Throwable thrown = catchThrowable(() -> service.chat("回答内容", session, List.of()));

    assertThat(thrown).isInstanceOf(BusinessException.class)
        .hasMessage("AI 模型关键配置不能为空");
    assertThat(((BusinessException) thrown).getCode())
        .isEqualTo(ErrorCode.PROVIDER_CONFIG_READ_FAILED.getCode());
  }

  @Test
  @DisplayName("流式调用失败不应推送兜底文本")
  void chatStreamShouldThrowBusinessExceptionWithoutFallbackText() {
    VoiceInterviewSessionEntity session = buildSession();
    List<String> emittedTexts = new ArrayList<>();
    when(promptService.generateSystemPromptWithContext("java-backend", null))
        .thenReturn("system prompt");
    when(aiClientFactory.getVoiceChatClient("u1"))
        .thenThrow(new RuntimeException("403 ACCESS_DENIED"));

    Throwable thrown = catchThrowable(() -> service.chatStreamSentences(
        "回答内容",
        emittedTexts::add,
        emittedTexts::add,
        session,
        List.of()
    ));

    assertThat(thrown).isInstanceOf(BusinessException.class)
        .hasMessage("AI 服务认证失败，请检查 API Key 配置");
    assertThat(((BusinessException) thrown).getCode())
        .isEqualTo(ErrorCode.AI_API_KEY_INVALID.getCode());
    assertThat(emittedTexts).isEmpty();
  }

  private VoiceInterviewSessionEntity buildSession() {
    return VoiceInterviewSessionEntity.builder()
        .id(1L)
        .userId("u1")
        .roleType("java-backend")
        .skillId("java-backend")
        .currentPhase(VoiceInterviewSessionEntity.InterviewPhase.TECH)
        .build();
  }
}
