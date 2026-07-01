package interview.guide.modules.interview.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import interview.guide.common.ai.AiClientFactory;
import interview.guide.common.ai.PromptSanitizer;
import interview.guide.common.ai.StructuredOutputInvoker;
import interview.guide.common.config.AiProperties;
import interview.guide.modules.aisettings.service.SystemAiSettingsResolver;
import interview.guide.modules.aisettings.service.SystemAiSettingsResolver.InterviewSnapshot;
import interview.guide.modules.aisettings.service.SystemAiSettingsResolver.RagSearchSnapshot;
import interview.guide.modules.aisettings.service.SystemAiSettingsResolver.ResumeWeightsSnapshot;
import interview.guide.modules.aisettings.service.SystemAiSettingsResolver.SystemAiSettingsSnapshot;
import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.interview.skill.InterviewSkillService;
import interview.guide.modules.interview.skill.InterviewSkillService.SkillCategoryDTO;
import interview.guide.modules.interview.skill.InterviewSkillService.SkillDTO;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.DefaultResourceLoader;

@ExtendWith(MockitoExtension.class)
@DisplayName("面试出题服务")
class InterviewQuestionServiceTest {

  @Mock
  private StructuredOutputInvoker structuredOutputInvoker;

  @Mock
  private InterviewSkillService skillService;

  @Mock
  private AiClientFactory aiClientFactory;

  @Mock
  private SystemAiSettingsResolver systemAiSettingsResolver;

  private InterviewQuestionService questionService;

  @BeforeEach
  void setUp() throws IOException {
    InterviewQuestionProperties properties = new InterviewQuestionProperties();
    properties.setFollowUpCount(1);

    questionService = new InterviewQuestionService(
        structuredOutputInvoker,
        skillService,
        properties,
        new DefaultResourceLoader(),
        aiClientFactory,
        new PromptSanitizer(new AiProperties()),
        systemAiSettingsResolver
    );

    when(systemAiSettingsResolver.resolve()).thenReturn(defaultSettings());
  }

  @Test
  @DisplayName("选择 6 题时追问不会让最终题目翻倍")
  void generateQuestionsShouldTreatRequestedCountAsFinalQuestionCount() {
    SkillDTO skill = javaBackendSkill();
    Map<String, Integer> allocation = new LinkedHashMap<>();
    allocation.put("JAVA", 1);
    allocation.put("SPRING", 1);
    allocation.put("MYSQL", 1);

    when(skillService.getSkill("java-backend")).thenReturn(skill);
    when(skillService.calculateAllocation(skill.categories(), 3)).thenReturn(allocation);
    when(skillService.buildAllocationDescription(allocation, skill.categories())).thenReturn("");
    when(skillService.buildReferenceSection(skill, allocation)).thenReturn("");
    when(structuredOutputInvoker.invoke(
        any(),
        anyString(),
        anyString(),
        any(),
        any(),
        any(),
        anyString(),
        anyString(),
        any()
    )).thenThrow(new IllegalStateException("force fallback"));

    List<InterviewQuestionDTO> questions = questionService.generateQuestionsBySkill(
        "java-backend",
        "mid",
        null,
        6,
        List.of(),
        null,
        null
    );

    assertThat(questions).hasSize(6);
    assertThat(questions).extracting(InterviewQuestionDTO::questionIndex)
        .containsExactly(0, 1, 2, 3, 4, 5);
    assertThat(questions.stream().filter(InterviewQuestionDTO::isFollowUp).count()).isEqualTo(3);
    assertThat(questions.stream().filter(question -> !question.isFollowUp()).count()).isEqualTo(3);
    assertThat(questions.stream()
        .filter(InterviewQuestionDTO::isFollowUp)
        .map(InterviewQuestionDTO::parentQuestionIndex)
        .toList()
    ).containsExactly(0, 2, 4);
    assertThat(questions.stream()
        .filter(InterviewQuestionDTO::isFollowUp)
        .map(InterviewQuestionDTO::category)
        .toList()
    ).containsExactly("Java 追问1", "Spring 追问1", "MySQL 追问1");
  }

  @Test
  @DisplayName("旧版英文追问分类会标准化为中文")
  void legacyFollowUpCategoryShouldBeNormalized() {
    InterviewQuestionDTO question = InterviewQuestionDTO.create(
        0,
        "请设计一个秒杀系统",
        "SYSTEM_DESIGN",
        "系统设计 follow-up 1",
        null,
        true,
        0
    );

    assertThat(question.category()).isEqualTo("系统设计 追问1");
    assertThat(InterviewQuestionDTO.normalizeCategory("follow-up 2")).isEqualTo("追问2");
  }

  private SkillDTO javaBackendSkill() {
    return new SkillDTO(
        "java-backend",
        "Java 后端",
        "Java backend interview",
        List.of(
            new SkillCategoryDTO("JAVA", "Java", "CORE", null, false),
            new SkillCategoryDTO("SPRING", "Spring", "CORE", null, false),
            new SkillCategoryDTO("MYSQL", "MySQL", "CORE", null, false)
        ),
        true,
        null,
        null,
        null
    );
  }

  private SystemAiSettingsSnapshot defaultSettings() {
    return new SystemAiSettingsSnapshot(
        new ResumeWeightsSnapshot(40, 20, 15, 15, 10),
        new InterviewSnapshot(60, 40, 0.2, 0.2, 0.2, 0.2),
        new RagSearchSnapshot(20, 12, 8, 0.18, 0.28, 0.28)
    );
  }
}
