package interview.guide.modules.interview.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.model.AsyncTaskStatus;
import interview.guide.modules.interview.model.CreateMistakePracticeResponse;
import interview.guide.modules.interview.model.InterviewAnswerEntity;
import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.interview.model.InterviewSessionDTO;
import interview.guide.modules.interview.model.InterviewSessionEntity;
import interview.guide.modules.interview.model.MistakeSessionDTO;
import interview.guide.modules.resume.model.ResumeEntity;
import interview.guide.modules.resume.repository.ResumeRepository;
import interview.guide.modules.voiceinterview.dto.VoiceEvaluationDetailDTO;
import interview.guide.modules.voiceinterview.model.VoiceInterviewEvaluationEntity;
import interview.guide.modules.voiceinterview.model.VoiceInterviewSessionEntity;
import interview.guide.modules.voiceinterview.repository.VoiceInterviewEvaluationRepository;
import interview.guide.modules.voiceinterview.repository.VoiceInterviewSessionRepository;
import interview.guide.modules.voiceinterview.service.VoiceInterviewEvaluationService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("错题服务")
class MistakeServiceTest {

  private InterviewPersistenceService interviewPersistenceService;
  private InterviewSessionService interviewSessionService;
  private VoiceInterviewEvaluationRepository voiceEvaluationRepository;
  private VoiceInterviewSessionRepository voiceSessionRepository;
  private VoiceInterviewEvaluationService voiceEvaluationService;
  private ResumeRepository resumeRepository;
  private ObjectMapper objectMapper;
  private MistakeService mistakeService;

  @BeforeEach
  void setUp() {
    interviewPersistenceService = mock(InterviewPersistenceService.class);
    interviewSessionService = mock(InterviewSessionService.class);
    voiceEvaluationRepository = mock(VoiceInterviewEvaluationRepository.class);
    voiceSessionRepository = mock(VoiceInterviewSessionRepository.class);
    voiceEvaluationService = mock(VoiceInterviewEvaluationService.class);
    resumeRepository = mock(ResumeRepository.class);
    objectMapper = new ObjectMapper();
    mistakeService = new MistakeService(
        interviewPersistenceService,
        interviewSessionService,
        voiceEvaluationRepository,
        voiceSessionRepository,
        voiceEvaluationService,
        resumeRepository,
        objectMapper
    );
  }

  @Test
  @DisplayName("文字面试只按场次返回 60 分以下题目")
  void listMistakesShouldGroupTextMistakesBySession() throws Exception {
    InterviewSessionEntity session = textSession(
        "text-1",
        InterviewSessionEntity.PracticeType.NORMAL,
        LocalDateTime.of(2026, 6, 1, 10, 0),
        72,
        "java-backend",
        "mid",
        questions()
    );
    InterviewAnswerEntity lowScore = textAnswer(
        session,
        0,
        "HashMap 扩容机制是什么？",
        "Java 集合",
        "回答不完整",
        59,
        "需要补充阈值和 rehash",
        "负载因子达到阈值后扩容并迁移桶",
        "[\"负载因子\",\"rehash\"]"
    );
    InterviewAnswerEntity passScore = textAnswer(
        session,
        1,
        "Spring Bean 生命周期？",
        "Spring",
        "回答完整",
        60,
        "达到合格线",
        "按创建、初始化、销毁阶段说明",
        "[\"初始化\"]"
    );
    InterviewAnswerEntity pendingScore = textAnswer(
        session,
        2,
        "索引失效场景？",
        "MySQL",
        "未评分",
        null,
        null,
        null,
        null
    );
    when(interviewPersistenceService.findAll()).thenReturn(List.of(session));
    when(interviewPersistenceService.findBySessionId("text-1")).thenReturn(Optional.of(session));
    when(interviewPersistenceService.findAnswersBySessionId("text-1"))
        .thenReturn(List.of(lowScore, passScore, pendingScore));
    when(voiceEvaluationRepository.findAll()).thenReturn(List.of());

    List<MistakeSessionDTO> result = mistakeService.listMistakes();

    assertThat(result).hasSize(1);
    MistakeSessionDTO mistakeSession = result.getFirst();
    assertThat(mistakeSession.sourceType()).isEqualTo("TEXT");
    assertThat(mistakeSession.sourceSessionId()).isEqualTo("text-1");
    assertThat(mistakeSession.overallScore()).isEqualTo(72);
    assertThat(mistakeSession.mistakeCount()).isEqualTo(1);
    assertThat(mistakeSession.practiceType()).isEqualTo("NORMAL");
    assertThat(mistakeSession.mistakes()).hasSize(1);
    MistakeSessionDTO.MistakeQuestionDTO mistake = mistakeSession.mistakes().getFirst();
    assertThat(mistake.questionIndex()).isZero();
    assertThat(mistake.question()).isEqualTo("HashMap 扩容机制是什么？");
    assertThat(mistake.type()).isEqualTo("JAVA");
    assertThat(mistake.category()).isEqualTo("Java 集合");
    assertThat(mistake.topicSummary()).isEqualTo("集合扩容");
    assertThat(mistake.score()).isEqualTo(59);
    assertThat(mistake.keyPoints()).containsExactly("负载因子", "rehash");
  }

  @Test
  @DisplayName("语音面试从评估详情中筛选 60 分以下题目")
  void listMistakesShouldGroupVoiceMistakesBySession() {
    VoiceInterviewEvaluationEntity evaluation = voiceEvaluation(7L, 58);
    VoiceInterviewSessionEntity session = voiceSession(
        7L,
        "Java 技术面",
        LocalDateTime.of(2026, 6, 2, 9, 30),
        12L,
        "java-backend",
        "hard"
    );
    VoiceEvaluationDetailDTO detail = VoiceEvaluationDetailDTO.builder()
        .sessionId(7L)
        .overallScore(58)
        .answers(List.of(
            voiceAnswer(0, "介绍一下 JVM 内存模型", "JVM", 55),
            voiceAnswer(1, "说说事务隔离级别", "MySQL", 88)
        ))
        .build();
    when(interviewPersistenceService.findAll()).thenReturn(List.of());
    when(voiceEvaluationRepository.findAll()).thenReturn(List.of(evaluation));
    when(voiceEvaluationRepository.findBySessionId(7L)).thenReturn(Optional.of(evaluation));
    when(voiceSessionRepository.findById(7L)).thenReturn(Optional.of(session));
    when(voiceEvaluationService.buildDetailDTO(evaluation)).thenReturn(detail);

    List<MistakeSessionDTO> result = mistakeService.listMistakes();

    assertThat(result).hasSize(1);
    MistakeSessionDTO mistakeSession = result.getFirst();
    assertThat(mistakeSession.sourceType()).isEqualTo("VOICE");
    assertThat(mistakeSession.sourceSessionId()).isEqualTo("7");
    assertThat(mistakeSession.title()).isEqualTo("Java 技术面");
    assertThat(mistakeSession.overallScore()).isEqualTo(58);
    assertThat(mistakeSession.mistakeCount()).isEqualTo(1);
    MistakeSessionDTO.MistakeQuestionDTO mistake = mistakeSession.mistakes().getFirst();
    assertThat(mistake.questionIndex()).isZero();
    assertThat(mistake.category()).isEqualTo("JVM");
    assertThat(mistake.referenceAnswer()).isEqualTo("参考答案: 介绍一下 JVM 内存模型");
    assertThat(mistake.keyPoints()).containsExactly("关键点");
  }

  @Test
  @DisplayName("错题重练场次不进入错题列表")
  void listMistakesShouldExcludeMistakeReviewSessions() throws Exception {
    InterviewSessionEntity practiceSession = textSession(
        "review-1",
        InterviewSessionEntity.PracticeType.MISTAKE_REVIEW,
        LocalDateTime.of(2026, 6, 3, 8, 0),
        45,
        "java-backend",
        "mid",
        questions()
    );
    when(interviewPersistenceService.findAll()).thenReturn(List.of(practiceSession));
    when(voiceEvaluationRepository.findAll()).thenReturn(List.of());

    List<MistakeSessionDTO> result = mistakeService.listMistakes();

    assertThat(result).isEmpty();
    verify(interviewPersistenceService, never()).findBySessionId("review-1");
    verify(interviewPersistenceService, never()).findAnswersBySessionId("review-1");
  }

  @Test
  @DisplayName("文字错题重练会创建新的文字面试场次")
  void createPracticeShouldCreateTextReviewSession() throws Exception {
    InterviewSessionEntity sourceSession = textSession(
        "text-1",
        InterviewSessionEntity.PracticeType.NORMAL,
        LocalDateTime.of(2026, 6, 4, 10, 0),
        50,
        "spring",
        "hard",
        questions()
    );
    sourceSession.setResumeId(10L);
    ResumeEntity resume = new ResumeEntity();
    resume.setId(10L);
    resume.setResumeText("resume text");
    InterviewAnswerEntity firstMistake = textAnswer(
        sourceSession,
        0,
        "HashMap 扩容机制是什么？",
        "Java 集合",
        "回答不完整",
        40,
        "需要补充阈值和 rehash",
        "负载因子达到阈值后扩容并迁移桶",
        null
    );
    InterviewAnswerEntity passed = textAnswer(
        sourceSession,
        1,
        "Spring Bean 生命周期？",
        "Spring",
        "回答完整",
        90,
        "很好",
        "按生命周期说明",
        null
    );
    InterviewAnswerEntity secondMistake = textAnswer(
        sourceSession,
        2,
        "索引失效场景？",
        "MySQL",
        "遗漏函数索引场景",
        35,
        "需要结合 SQL 示例",
        "函数、隐式转换和最左前缀问题",
        null
    );
    when(interviewPersistenceService.findBySessionId("text-1"))
        .thenReturn(Optional.of(sourceSession));
    when(interviewPersistenceService.findAnswersBySessionId("text-1"))
        .thenReturn(List.of(firstMistake, passed, secondMistake));
    when(resumeRepository.findById(10L)).thenReturn(Optional.of(resume));
    when(interviewSessionService.createMistakeReviewSession(
        eq("resume text"),
        eq(10L),
        anyList(),
        eq("spring"),
        eq("hard"),
        eq(InterviewSessionEntity.MistakeSourceType.TEXT),
        eq("text-1")
    )).thenAnswer(invocation -> new InterviewSessionDTO(
        "new-session",
        "resume text",
        2,
        0,
        invocation.getArgument(2),
        InterviewSessionDTO.SessionStatus.CREATED
    ));

    CreateMistakePracticeResponse response = mistakeService.createPractice("text", "text-1");

    assertThat(response.sessionId()).isEqualTo("new-session");
    verify(interviewSessionService).createMistakeReviewSession(
        eq("resume text"),
        eq(10L),
        argThat(MistakeServiceTest::matchesRebuiltTextPracticeQuestions),
        eq("spring"),
        eq("hard"),
        eq(InterviewSessionEntity.MistakeSourceType.TEXT),
        eq("text-1")
    );
  }

  @Test
  @DisplayName("来源场次不存在时抛出业务异常")
  void getMistakeSessionShouldThrowBusinessExceptionWhenTextSessionMissing() {
    when(interviewPersistenceService.findBySessionId("missing")).thenReturn(Optional.empty());

    Throwable thrown = catchThrowable(() -> mistakeService.getMistakeSession("TEXT", "missing"));

    assertThat(thrown).isInstanceOf(BusinessException.class);
    assertThat(((BusinessException) thrown).getCode())
        .isEqualTo(ErrorCode.INTERVIEW_SESSION_NOT_FOUND.getCode());
  }

  @Test
  @DisplayName("语音来源场次 ID 非数字时抛出业务异常")
  void getMistakeSessionShouldThrowBusinessExceptionWhenVoiceSessionIdInvalid() {
    Throwable thrown = catchThrowable(() -> mistakeService.getMistakeSession("VOICE", "bad-id"));

    assertThat(thrown).isInstanceOf(BusinessException.class);
    assertThat(((BusinessException) thrown).getCode()).isEqualTo(ErrorCode.BAD_REQUEST.getCode());
  }

  @Test
  @DisplayName("来源场次没有错题时抛出业务异常")
  void getMistakeSessionShouldThrowBusinessExceptionWhenNoMistakes() throws Exception {
    InterviewSessionEntity session = textSession(
        "text-1",
        InterviewSessionEntity.PracticeType.NORMAL,
        LocalDateTime.of(2026, 6, 5, 10, 0),
        95,
        "java-backend",
        "mid",
        questions()
    );
    InterviewAnswerEntity passed = textAnswer(
        session,
        0,
        "HashMap 扩容机制是什么？",
        "Java 集合",
        "回答完整",
        80,
        "不错",
        "负载因子达到阈值后扩容并迁移桶",
        null
    );
    when(interviewPersistenceService.findBySessionId("text-1")).thenReturn(Optional.of(session));
    when(interviewPersistenceService.findAnswersBySessionId("text-1")).thenReturn(List.of(passed));

    Throwable thrown = catchThrowable(() -> mistakeService.getMistakeSession("TEXT", "text-1"));

    assertThat(thrown).isInstanceOf(BusinessException.class);
    assertThat(((BusinessException) thrown).getCode())
        .isEqualTo(ErrorCode.INTERVIEW_QUESTION_NOT_FOUND.getCode());
  }

  private InterviewSessionEntity textSession(
      String sessionId,
      InterviewSessionEntity.PracticeType practiceType,
      LocalDateTime createdAt,
      Integer overallScore,
      String skillId,
      String difficulty,
      List<InterviewQuestionDTO> questions
  ) throws Exception {
    InterviewSessionEntity session = new InterviewSessionEntity();
    session.setSessionId(sessionId);
    session.setPracticeType(practiceType);
    session.setStatus(InterviewSessionEntity.SessionStatus.EVALUATED);
    session.setEvaluateStatus(AsyncTaskStatus.COMPLETED);
    session.setCreatedAt(createdAt);
    session.setOverallScore(overallScore);
    session.setSkillId(skillId);
    session.setDifficulty(difficulty);
    session.setTotalQuestions(questions.size());
    session.setQuestionsJson(objectMapper.writeValueAsString(questions));
    return session;
  }

  private List<InterviewQuestionDTO> questions() {
    return List.of(
        InterviewQuestionDTO.create(
            0,
            "HashMap 扩容机制是什么？",
            "JAVA",
            "Java 集合",
            "集合扩容",
            false,
            null
        ),
        InterviewQuestionDTO.create(
            1,
            "Spring Bean 生命周期？",
            "SPRING",
            "Spring",
            "Bean 生命周期",
            false,
            null
        ),
        InterviewQuestionDTO.create(
            2,
            "索引失效场景？",
            "MYSQL",
            "MySQL",
            "索引优化",
            false,
            null
        )
    );
  }

  private InterviewAnswerEntity textAnswer(
      InterviewSessionEntity session,
      int questionIndex,
      String question,
      String category,
      String userAnswer,
      Integer score,
      String feedback,
      String referenceAnswer,
      String keyPointsJson
  ) {
    InterviewAnswerEntity answer = new InterviewAnswerEntity();
    answer.setSession(session);
    answer.setQuestionIndex(questionIndex);
    answer.setQuestion(question);
    answer.setCategory(category);
    answer.setUserAnswer(userAnswer);
    answer.setScore(score);
    answer.setFeedback(feedback);
    answer.setReferenceAnswer(referenceAnswer);
    answer.setKeyPointsJson(keyPointsJson);
    return answer;
  }

  private VoiceInterviewEvaluationEntity voiceEvaluation(Long sessionId, Integer overallScore) {
    VoiceInterviewEvaluationEntity evaluation = new VoiceInterviewEvaluationEntity();
    evaluation.setSessionId(sessionId);
    evaluation.setOverallScore(overallScore);
    return evaluation;
  }

  private VoiceInterviewSessionEntity voiceSession(
      Long sessionId,
      String roleType,
      LocalDateTime createdAt,
      Long resumeId,
      String skillId,
      String difficulty
  ) {
    VoiceInterviewSessionEntity session = new VoiceInterviewSessionEntity();
    session.setId(sessionId);
    session.setRoleType(roleType);
    session.setCreatedAt(createdAt);
    session.setResumeId(resumeId);
    session.setSkillId(skillId);
    session.setDifficulty(difficulty);
    return session;
  }

  private VoiceEvaluationDetailDTO.AnswerDetail voiceAnswer(
      int questionIndex,
      String question,
      String category,
      int score
  ) {
    return VoiceEvaluationDetailDTO.AnswerDetail.builder()
        .questionIndex(questionIndex)
        .question(question)
        .category(category)
        .userAnswer("用户回答: " + question)
        .score(score)
        .feedback("反馈: " + question)
        .referenceAnswer("参考答案: " + question)
        .keyPoints(List.of("关键点"))
        .build();
  }

  private static boolean matchesRebuiltTextPracticeQuestions(
      List<InterviewQuestionDTO> practiceQuestions
  ) {
    if (practiceQuestions == null || practiceQuestions.size() != 2) {
      return false;
    }
    InterviewQuestionDTO first = practiceQuestions.getFirst();
    InterviewQuestionDTO second = practiceQuestions.get(1);
    return first.questionIndex() == 0
        && second.questionIndex() == 1
        && "HashMap 扩容机制是什么？".equals(first.question())
        && "索引失效场景？".equals(second.question())
        && "JAVA".equals(first.type())
        && "集合扩容".equals(first.topicSummary())
        && "MYSQL".equals(second.type())
        && "索引优化".equals(second.topicSummary());
  }
}
