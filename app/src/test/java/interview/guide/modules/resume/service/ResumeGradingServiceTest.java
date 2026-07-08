package interview.guide.modules.resume.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import interview.guide.common.ai.AiClientFactory;
import interview.guide.common.ai.StructuredOutputInvoker;
import interview.guide.modules.aisettings.service.SystemAiSettingsResolver;
import interview.guide.modules.aisettings.service.SystemAiSettingsResolver.InterviewSnapshot;
import interview.guide.modules.aisettings.service.SystemAiSettingsResolver.RagSearchSnapshot;
import interview.guide.modules.aisettings.service.SystemAiSettingsResolver.ResumeWeightsSnapshot;
import interview.guide.modules.aisettings.service.SystemAiSettingsResolver.SystemAiSettingsSnapshot;
import interview.guide.modules.interview.model.ResumeAnalysisResponse;
import java.io.IOException;
import java.util.List;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.core.io.DefaultResourceLoader;

@ExtendWith(MockitoExtension.class)
@DisplayName("Resume grading service")
class ResumeGradingServiceTest {

    @Mock
    private AiClientFactory aiClientFactory;

    @Mock
    private StructuredOutputInvoker structuredOutputInvoker;

    @Mock
    private SystemAiSettingsResolver systemAiSettingsResolver;

    private ResumeGradingService service;

    @BeforeEach
    void setUp() throws IOException {
        service = new ResumeGradingService(
                aiClientFactory,
                systemAiSettingsResolver,
                structuredOutputInvoker,
                new ResumeAnalysisProperties(),
                new DefaultResourceLoader()
        );
    }

    @Test
    @DisplayName("system weights should change the final score")
    void analyzeResumeShouldApplySystemWeights() {
        ChatClient chatClient = mock(ChatClient.class);
        when(aiClientFactory.getDefaultChatClient("user-1")).thenReturn(chatClient);
        when(systemAiSettingsResolver.resolve()).thenReturn(new SystemAiSettingsSnapshot(
                new ResumeWeightsSnapshot(100, 0, 0, 0, 0),
                new InterviewSnapshot(60, 40, 0.2, 0.2, 0.2, 0.2),
                new RagSearchSnapshot(20, 12, 8, 0.18, 0.28, 0.28)
        ));

        ResumeGradingService.ResumeAnalysisResponseDTO dto =
            new ResumeGradingService.ResumeAnalysisResponseDTO(
                0,
                new ResumeGradingService.ScoreDetailDTO(15, 10, 20, 8, 30),
                "summary",
                List.of("strength"),
                List.of(new ResumeGradingService.SuggestionDTO(
                    "project",
                    "high",
                    "issue",
                    "recommendation"
                ))
            );

        when(structuredOutputInvoker.invoke(
                any(ChatClient.class),
                anyString(),
                anyString(),
                any(BeanOutputConverter.class),
                any(),
                any(),
                anyString(),
                anyString(),
                any()
        )).thenReturn(dto);

        ResumeAnalysisResponse result = service.analyzeResume("user-1", "resume text");

        assertThat(result.overallScore()).isEqualTo(40);

        ArgumentCaptor<String> systemPromptCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> userPromptCaptor = ArgumentCaptor.forClass(String.class);
        verify(structuredOutputInvoker).invoke(
                any(ChatClient.class),
                systemPromptCaptor.capture(),
                userPromptCaptor.capture(),
                any(BeanOutputConverter.class),
                any(),
                any(),
                anyString(),
                any()
        );

        assertThat(systemPromptCaptor.getValue())
                .contains("projectTechDepth: 100%")
                .contains("skillMatchScore: 0%")
                .contains("overallScore = round(projectScore * 100 / 40")
                .doesNotContain("{projectWeight}");
        assertThat(userPromptCaptor.getValue()).contains("resume text");
    }
}
