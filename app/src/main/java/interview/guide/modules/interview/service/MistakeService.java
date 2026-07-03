package interview.guide.modules.interview.service;

import interview.guide.common.auth.CurrentUserContext;
import interview.guide.common.constant.CommonConstants.InterviewDefaults;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.model.AsyncTaskStatus;
import interview.guide.modules.interview.model.CreateMistakePracticeResponse;
import interview.guide.modules.interview.model.InterviewAnswerEntity;
import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.interview.model.InterviewSessionDTO;
import interview.guide.modules.interview.model.InterviewSessionEntity;
import interview.guide.modules.interview.model.MistakeSessionDTO;
import interview.guide.modules.interview.model.MistakeSessionDTO.MistakeQuestionDTO;
import interview.guide.modules.resume.model.ResumeEntity;
import interview.guide.modules.resume.repository.ResumeRepository;
import interview.guide.modules.voiceinterview.dto.VoiceEvaluationDetailDTO;
import interview.guide.modules.voiceinterview.model.VoiceInterviewEvaluationEntity;
import interview.guide.modules.voiceinterview.model.VoiceInterviewSessionEntity;
import interview.guide.modules.voiceinterview.repository.VoiceInterviewEvaluationRepository;
import interview.guide.modules.voiceinterview.repository.VoiceInterviewSessionRepository;
import interview.guide.modules.voiceinterview.service.VoiceInterviewEvaluationService;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * 错题服务
 * 负责错题的查询、详情获取和错题重练会话创建，
 * 同时聚合文字面试和语音面试两种来源的错题，按创建时间降序排列
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MistakeService {

    private static final int MISTAKE_SCORE_THRESHOLD = 60;
    private static final String TEXT_SOURCE = "TEXT";
    private static final String VOICE_SOURCE = "VOICE";
    private static final String DEFAULT_MISTAKE_TYPE = "MISTAKE_REVIEW";

    private final InterviewPersistenceService interviewPersistenceService;
    private final InterviewSessionService interviewSessionService;
    private final VoiceInterviewEvaluationRepository voiceEvaluationRepository;
    private final VoiceInterviewSessionRepository voiceSessionRepository;
    private final VoiceInterviewEvaluationService voiceEvaluationService;
    private final ResumeRepository resumeRepository;
    private final ObjectMapper objectMapper;

    /**
     * 获取当前用户的所有错题列表
     * 合并文字面试和语音面试的错题，按创建时间降序排列
     */
    public List<MistakeSessionDTO> listMistakes() {
        List<MistakeSessionDTO> result = new ArrayList<>();
        result.addAll(listTextMistakes());
        result.addAll(listVoiceMistakes());
        return result.stream()
                .sorted(Comparator.comparing(MistakeSessionDTO::createdAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
    }

    /**
     * 获取指定来源的错题详情
     */
    public MistakeSessionDTO getMistakeSession(String sourceType, String sourceSessionId) {
        return switch (normalizeSourceType(sourceType)) {
            case TEXT_SOURCE -> buildTextMistakeSession(sourceSessionId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.INTERVIEW_QUESTION_NOT_FOUND,
                            "该文字面试暂无错题"));
            case VOICE_SOURCE -> buildVoiceMistakeSession(parseVoiceSessionId(sourceSessionId))
                    .orElseThrow(() -> new BusinessException(ErrorCode.INTERVIEW_QUESTION_NOT_FOUND,
                            "该语音面试暂无错题"));
            default -> throw new BusinessException(ErrorCode.BAD_REQUEST, "不支持的错题来源: " + sourceType);
        };
    }

    /**
     * 基于错题创建重练会话
     * 提取错题中的问题，复用原面试的技能和简历上下文创建新的练习会话
     */
    public CreateMistakePracticeResponse createPractice(String sourceType, String sourceSessionId) {
        String normalizedType = normalizeSourceType(sourceType);
        MistakeSessionDTO mistakeSession = getMistakeSession(normalizedType, sourceSessionId);
        // 将错题重新构建为面试题目列表
        List<InterviewQuestionDTO> questions = rebuildPracticeQuestions(mistakeSession);

        InterviewSessionEntity.MistakeSourceType sourceEnum =
                InterviewSessionEntity.MistakeSourceType.valueOf(normalizedType);
        PracticeContext context = buildPracticeContext(normalizedType, sourceSessionId);

        // 创建错题重练会话
        InterviewSessionDTO practiceSession = interviewSessionService.createMistakeReviewSession(
                context.resumeText(),
                context.resumeId(),
                questions,
                context.skillId(),
                context.difficulty(),
                sourceEnum,
                sourceSessionId
        );

        return new CreateMistakePracticeResponse(practiceSession.sessionId());
    }

    /**
     * 查询所有文字面试的错题
     * 过滤掉错题重练类型的会话，仅统计已评估完成的会话
     */
    private List<MistakeSessionDTO> listTextMistakes() {
        return interviewPersistenceService.findAll().stream()
                .filter(session -> session.getPracticeType() != InterviewSessionEntity.PracticeType.MISTAKE_REVIEW)
                .filter(session -> session.getStatus() == InterviewSessionEntity.SessionStatus.EVALUATED
                        || AsyncTaskStatus.COMPLETED.equals(session.getEvaluateStatus()))
                .map(session -> buildTextMistakeSession(session.getSessionId()))
                .flatMap(Optional::stream)
                .toList();
    }

    /**
     * 构建单次文字面试的错题详情
     * 筛选分数低于阈值的回答作为错题
     */
    private Optional<MistakeSessionDTO> buildTextMistakeSession(String sessionId) {
        InterviewSessionEntity session = interviewPersistenceService.findBySessionIdForCurrentUser(sessionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.INTERVIEW_SESSION_NOT_FOUND));

        List<InterviewAnswerEntity> answers = interviewPersistenceService.findAnswersBySessionId(sessionId);
        List<InterviewQuestionDTO> sourceQuestions = parseQuestions(session);
        // 构建题目索引映射，方便按序号查找题目信息
        Map<Integer, InterviewQuestionDTO> questionMap = sourceQuestions.stream()
                .collect(Collectors.toMap(
                        InterviewQuestionDTO::questionIndex,
                        question -> question,
                        (first, second) -> first
                ));

        // 筛选分数低于阈值的回答作为错题
        List<MistakeQuestionDTO> mistakes = answers.stream()
                .filter(answer -> answer.getScore() != null && answer.getScore() < MISTAKE_SCORE_THRESHOLD)
                .map(answer -> toTextMistakeQuestion(answer, questionMap.get(answer.getQuestionIndex())))
                .toList();

        if (mistakes.isEmpty()) {
            return Optional.empty();
        }

        return Optional.of(new MistakeSessionDTO(
                TEXT_SOURCE,
                session.getSessionId(),
                buildTextTitle(session),
                session.getCreatedAt(),
                session.getOverallScore(),
                mistakes.size(),
                mistakes,
                session.getPracticeType() != null ? session.getPracticeType().name() : null
        ));
    }

    /**
     * 查询所有语音面试的错题
     */
    private List<MistakeSessionDTO> listVoiceMistakes() {
        return voiceSessionRepository.findByUserIdOrderByUpdatedAtDesc(CurrentUserContext.getRequiredUserId()).stream()
                .map(VoiceInterviewSessionEntity::getId)
                .map(this::safeBuildVoiceMistakeSession)
                .flatMap(Optional::stream)
                .toList();
    }

    /**
     * 安全构建语音面试错题，单个会话异常时跳过而不中断整体查询
     */
    private Optional<MistakeSessionDTO> safeBuildVoiceMistakeSession(Long sessionId) {
        try {
            return buildVoiceMistakeSession(sessionId);
        } catch (BusinessException e) {
            log.warn("跳过无效的语音错题记录: sessionId={}, message={}", sessionId, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 构建单次语音面试的错题详情
     */
    private Optional<MistakeSessionDTO> buildVoiceMistakeSession(Long sessionId) {
        VoiceInterviewEvaluationEntity evaluation = voiceEvaluationRepository.findBySessionId(sessionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.VOICE_EVALUATION_NOT_FOUND,
                        "语音面试评估结果不存在: " + sessionId));
        VoiceInterviewSessionEntity session = voiceSessionRepository.findByIdAndUserId(
                        sessionId,
                        CurrentUserContext.getRequiredUserId())
                .orElseThrow(() -> new BusinessException(ErrorCode.VOICE_SESSION_NOT_FOUND,
                        "语音面试会话不存在: " + sessionId));

        VoiceEvaluationDetailDTO detail = voiceEvaluationService.buildDetailDTO(evaluation);
        // 筛选分数低于阈值的回答作为错题
        List<MistakeQuestionDTO> mistakes = detail.getAnswers().stream()
                .filter(answer -> answer.getScore() < MISTAKE_SCORE_THRESHOLD)
                .map(this::toVoiceMistakeQuestion)
                .toList();

        if (mistakes.isEmpty()) {
            return Optional.empty();
        }

        return Optional.of(new MistakeSessionDTO(
                VOICE_SOURCE,
                String.valueOf(session.getId()),
                session.getRoleType(),
                session.getCreatedAt(),
                evaluation.getOverallScore(),
                mistakes.size(),
                mistakes,
                null
        ));
    }

    /**
     * 将文字面试回答转换为错题DTO
     */
    private MistakeQuestionDTO toTextMistakeQuestion(
            InterviewAnswerEntity answer,
            InterviewQuestionDTO sourceQuestion
    ) {
        return new MistakeQuestionDTO(
                answer.getQuestionIndex(),
                answer.getQuestion(),
                sourceQuestion != null ? sourceQuestion.type() : DEFAULT_MISTAKE_TYPE,
                answer.getCategory(),
                sourceQuestion != null ? sourceQuestion.topicSummary() : null,
                answer.getUserAnswer(),
                answer.getScore(),
                answer.getFeedback(),
                answer.getReferenceAnswer(),
                parseKeyPoints(answer.getKeyPointsJson())
        );
    }

    /**
     * 将语音面试回答转换为错题DTO
     */
    private MistakeQuestionDTO toVoiceMistakeQuestion(VoiceEvaluationDetailDTO.AnswerDetail answer) {
        return new MistakeQuestionDTO(
                answer.getQuestionIndex(),
                answer.getQuestion(),
                defaultIfBlank(answer.getCategory(), DEFAULT_MISTAKE_TYPE),
                defaultIfBlank(answer.getCategory(), "综合问题"),
                answer.getFeedback(),
                answer.getUserAnswer(),
                answer.getScore(),
                answer.getFeedback(),
                answer.getReferenceAnswer(),
                answer.getKeyPoints()
        );
    }

    /**
     * 将错题重新构建为面试题目列表
     * 用于创建错题重练会话时作为题目输入
     */
    private List<InterviewQuestionDTO> rebuildPracticeQuestions(MistakeSessionDTO mistakeSession) {
        List<MistakeQuestionDTO> mistakes = mistakeSession.mistakes();
        List<InterviewQuestionDTO> questions = new ArrayList<>();
        for (int i = 0; i < mistakes.size(); i++) {
            MistakeQuestionDTO mistake = mistakes.get(i);
            questions.add(InterviewQuestionDTO.create(
                    i,
                    mistake.question(),
                    defaultIfBlank(mistake.type(), DEFAULT_MISTAKE_TYPE),
                    defaultIfBlank(mistake.category(), "错题重练"),
                    mistake.topicSummary(),
                    false,
                    null
            ));
        }
        return questions;
    }

    /**
     * 构建错题重练的上下文信息
     * 复用原面试的简历和技能配置
     */
    private PracticeContext buildPracticeContext(String sourceType, String sourceSessionId) {
        if (TEXT_SOURCE.equals(sourceType)) {
            InterviewSessionEntity session = interviewPersistenceService.findBySessionIdForCurrentUser(sourceSessionId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.INTERVIEW_SESSION_NOT_FOUND));
            ResumeEntity resume = session.getResumeId() != null
                    ? resumeRepository.findById(session.getResumeId()).orElse(null)
                    : null;
            return new PracticeContext(
                    resume != null ? resume.getResumeText() : "",
                    session.getResumeId(),
                    session.getSkillId(),
                    session.getDifficulty()
            );
        }

        Long voiceSessionId = parseVoiceSessionId(sourceSessionId);
        VoiceInterviewSessionEntity session = voiceSessionRepository.findByIdAndUserId(
                        voiceSessionId,
                        CurrentUserContext.getRequiredUserId())
                .orElseThrow(() -> new BusinessException(ErrorCode.VOICE_SESSION_NOT_FOUND,
                        "语音面试会话不存在: " + sourceSessionId));

        return new PracticeContext(
                "",
                session.getResumeId(),
                session.getSkillId(),
                session.getDifficulty()
        );
    }

    /**
     * 从会话实体的JSON字段中解析题目列表
     */
    private List<InterviewQuestionDTO> parseQuestions(InterviewSessionEntity session) {
        String json = session.getQuestionsJson();
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (JacksonException e) {
            log.error("解析面试题目失败: sessionId={}", session.getSessionId(), e);
            return List.of();
        }
    }

    /**
     * 从JSON中解析关键点列表
     */
    private List<String> parseKeyPoints(String keyPointsJson) {
        if (keyPointsJson == null || keyPointsJson.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(keyPointsJson, new TypeReference<>() {});
        } catch (JacksonException e) {
            log.error("解析错题关键点失败", e);
            return null;
        }
    }

    /**
     * 构建文字面试错题的标题
     */
    private String buildTextTitle(InterviewSessionEntity session) {
        String skillId = defaultIfBlank(session.getSkillId(), InterviewDefaults.SKILL_ID);
        if (session.getPracticeType() == InterviewSessionEntity.PracticeType.MISTAKE_REVIEW) {
            return skillId + " 错题重练";
        }
        return skillId;
    }

    /**
     * 标准化来源类型为大写
     */
    private String normalizeSourceType(String sourceType) {
        if (sourceType == null || sourceType.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "错题来源不能为空");
        }
        return sourceType.toUpperCase(Locale.ROOT);
    }

    /**
     * 解析语音面试会话ID
     */
    private Long parseVoiceSessionId(String sourceSessionId) {
        try {
            return Long.parseLong(sourceSessionId);
        } catch (NumberFormatException e) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "语音面试场次 ID 无效: " + sourceSessionId);
        }
    }

    /**
     * 字符串为空时返回默认值
     */
    private String defaultIfBlank(String value, String fallback) {
        return value != null && !value.isBlank() ? value : fallback;
    }

    /**
     * 错题重练上下文信息
     */
    private record PracticeContext(
            String resumeText, // 简历文本
            Long resumeId, // 简历ID
            String skillId, // 技能方向ID
            String difficulty // 难度等级
    ) {
    }
}