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
 * 错题服务。
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

    public List<MistakeSessionDTO> listMistakes() {
        List<MistakeSessionDTO> result = new ArrayList<>();
        result.addAll(listTextMistakes());
        result.addAll(listVoiceMistakes());
        return result.stream()
                .sorted(Comparator.comparing(MistakeSessionDTO::createdAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
    }

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

    public CreateMistakePracticeResponse createPractice(String sourceType, String sourceSessionId) {
        String normalizedType = normalizeSourceType(sourceType);
        MistakeSessionDTO mistakeSession = getMistakeSession(normalizedType, sourceSessionId);
        List<InterviewQuestionDTO> questions = rebuildPracticeQuestions(mistakeSession);

        InterviewSessionEntity.MistakeSourceType sourceEnum =
                InterviewSessionEntity.MistakeSourceType.valueOf(normalizedType);
        PracticeContext context = buildPracticeContext(normalizedType, sourceSessionId);

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

    private List<MistakeSessionDTO> listTextMistakes() {
        return interviewPersistenceService.findAll().stream()
                .filter(session -> session.getPracticeType() != InterviewSessionEntity.PracticeType.MISTAKE_REVIEW)
                .filter(session -> session.getStatus() == InterviewSessionEntity.SessionStatus.EVALUATED
                        || AsyncTaskStatus.COMPLETED.equals(session.getEvaluateStatus()))
                .map(session -> buildTextMistakeSession(session.getSessionId()))
                .flatMap(Optional::stream)
                .toList();
    }

    private Optional<MistakeSessionDTO> buildTextMistakeSession(String sessionId) {
        InterviewSessionEntity session = interviewPersistenceService.findBySessionIdForCurrentUser(sessionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.INTERVIEW_SESSION_NOT_FOUND));

        List<InterviewAnswerEntity> answers = interviewPersistenceService.findAnswersBySessionId(sessionId);
        List<InterviewQuestionDTO> sourceQuestions = parseQuestions(session);
        Map<Integer, InterviewQuestionDTO> questionMap = sourceQuestions.stream()
                .collect(Collectors.toMap(
                        InterviewQuestionDTO::questionIndex,
                        question -> question,
                        (first, second) -> first
                ));

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

    private List<MistakeSessionDTO> listVoiceMistakes() {
        return voiceSessionRepository.findByUserIdOrderByUpdatedAtDesc(CurrentUserContext.getRequiredUserId()).stream()
                .map(VoiceInterviewSessionEntity::getId)
                .map(this::safeBuildVoiceMistakeSession)
                .flatMap(Optional::stream)
                .toList();
    }

    private Optional<MistakeSessionDTO> safeBuildVoiceMistakeSession(Long sessionId) {
        try {
            return buildVoiceMistakeSession(sessionId);
        } catch (BusinessException e) {
            log.warn("跳过无效的语音错题记录: sessionId={}, message={}", sessionId, e.getMessage());
            return Optional.empty();
        }
    }

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

    private String buildTextTitle(InterviewSessionEntity session) {
        String skillId = defaultIfBlank(session.getSkillId(), InterviewDefaults.SKILL_ID);
        if (session.getPracticeType() == InterviewSessionEntity.PracticeType.MISTAKE_REVIEW) {
            return skillId + " 错题重练";
        }
        return skillId;
    }

    private String normalizeSourceType(String sourceType) {
        if (sourceType == null || sourceType.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "错题来源不能为空");
        }
        return sourceType.toUpperCase(Locale.ROOT);
    }

    private Long parseVoiceSessionId(String sourceSessionId) {
        try {
            return Long.parseLong(sourceSessionId);
        } catch (NumberFormatException e) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "语音面试场次 ID 无效: " + sourceSessionId);
        }
    }

    private String defaultIfBlank(String value, String fallback) {
        return value != null && !value.isBlank() ? value : fallback;
    }

    private record PracticeContext(
            String resumeText,
            Long resumeId,
            String skillId,
            String difficulty
    ) {
    }
}
