package interview.guide.infrastructure.mapper;

import interview.guide.modules.interview.model.InterviewAnswerEntity;
import interview.guide.modules.interview.model.InterviewDetailDTO;
import interview.guide.modules.interview.model.InterviewHistoryItemDTO;
import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.interview.model.InterviewReportDTO;
import interview.guide.modules.interview.model.InterviewSessionEntity;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.mapstruct.Named;
import org.mapstruct.ReportingPolicy;

/**
 * 面试相关的对象映射器
 * 使用MapStruct自动生成转换代码
 * <p>
 * 注意：JSON字段需要在Service层手动处理
 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface InterviewMapper {

    // ========== QuestionEvaluation 映射 ==========

    /**
     * 将面试答案实体转换为问题评估详情
     */
    @Mapping(target = "questionIndex", source = "questionIndex", qualifiedByName = "nullIndexToZero")
    @Mapping(target = "question", source = "question")
    @Mapping(target = "category", source = "category", qualifiedByName = "normalizeCategory")
    @Mapping(target = "userAnswer", source = "userAnswer")
    @Mapping(target = "score", source = "score", qualifiedByName = "nullScoreToZero")
    @Mapping(target = "feedback", source = "feedback")
    InterviewReportDTO.QuestionEvaluation toQuestionEvaluation(InterviewAnswerEntity entity);

    /**
     * 批量转换面试答案实体
     */
    List<InterviewReportDTO.QuestionEvaluation> toQuestionEvaluations(List<InterviewAnswerEntity> entities);

    // ========== AnswerDetailDTO 映射 ==========

    /**
     * InterviewAnswerEntity 转换为 AnswerDetailDTO
     * 注意：keyPoints 需要从 JSON 解析后传入
     */
    @Mapping(target = "category", source = "entity.category", qualifiedByName = "normalizeCategory")
    @Mapping(target = "keyPoints", source = "keyPoints")
    InterviewDetailDTO.AnswerDetailDTO toAnswerDetailDTO(
        InterviewAnswerEntity entity,
        List<String> keyPoints
    );

    /**
     * 将未回答的问题映射为空答案占位详情。
     */
    @Mapping(target = "questionIndex", source = "questionIndex")
    @Mapping(target = "question", source = "question")
    @Mapping(target = "category", source = "category", qualifiedByName = "normalizeCategory")
    @Mapping(target = "userAnswer", ignore = true)
    @Mapping(target = "score", source = "score", qualifiedByName = "nullScoreToZero")
    @Mapping(target = "feedback", source = "feedback")
    @Mapping(target = "referenceAnswer", ignore = true)
    @Mapping(target = "keyPoints", ignore = true)
    @Mapping(target = "answeredAt", ignore = true)
    InterviewDetailDTO.AnswerDetailDTO toMissingAnswerDetailDTO(InterviewQuestionDTO question);

    /**
     * 批量转换（需要在 Service 层处理 JSON）
     */
    default List<InterviewDetailDTO.AnswerDetailDTO> toAnswerDetailDTOList(
        List<InterviewAnswerEntity> entities,
        Function<InterviewAnswerEntity, List<String>> keyPointsExtractor
    ) {
        List<InterviewAnswerEntity> safeEntities = entities != null ? entities : List.of();
        return safeEntities.stream()
            .map(e -> toAnswerDetailDTO(e, keyPointsExtractor.apply(e)))
            .toList();
    }

    /**
     * 按题目列表组装完整答案详情，未回答题目使用空答案占位。
     */
    default List<InterviewDetailDTO.AnswerDetailDTO> toAnswerDetailDTOList(
        List<InterviewQuestionDTO> allQuestions,
        List<InterviewAnswerEntity> answers,
        Function<InterviewAnswerEntity, List<String>> keyPointsExtractor
    ) {
        List<InterviewAnswerEntity> safeAnswers = answers != null ? answers : List.of();
        if (allQuestions == null || allQuestions.isEmpty()) {
            return toAnswerDetailDTOList(safeAnswers, keyPointsExtractor);
        }

        Map<Integer, InterviewAnswerEntity> answerMap = safeAnswers.stream()
            .collect(Collectors.toMap(
                InterviewAnswerEntity::getQuestionIndex,
                answer -> answer,
                (first, second) -> first
            ));

        return allQuestions.stream()
            .map(question -> {
                InterviewAnswerEntity answer = answerMap.get(question.questionIndex());
                if (answer != null) {
                    return toAnswerDetailDTO(answer, keyPointsExtractor.apply(answer));
                }
                return toMissingAnswerDetailDTO(question);
            })
            .toList();
    }

    // ========== InterviewDetailDTO 映射 ==========

    /**
     * InterviewSessionEntity 转换为 InterviewDetailDTO
     * 注意：questions, strengths, improvements, referenceAnswers, answers 需要在 Service 层处理
     */
    @Mapping(target = "status", expression = "java(session.getStatus().toString())")
    @Mapping(target = "evaluateStatus", expression = "java(session.getEvaluateStatus() != null ? session.getEvaluateStatus().name() : null)")
    @Mapping(target = "evaluateError", source = "session.evaluateError")
    @Mapping(target = "questions", source = "questions")
    @Mapping(target = "strengths", source = "strengths")
    @Mapping(target = "improvements", source = "improvements")
    @Mapping(target = "referenceAnswers", source = "referenceAnswers")
    @Mapping(target = "answers", source = "answers")
    InterviewDetailDTO toDetailDTO(
        InterviewSessionEntity session,
        List<Object> questions,
        List<String> strengths,
        List<String> improvements,
        List<Object> referenceAnswers,
        List<InterviewDetailDTO.AnswerDetailDTO> answers
    );

    // ========== InterviewSessionEntity 更新映射 ==========

    /**
     * 从 InterviewReportDTO 更新 InterviewSessionEntity
     * 注意：JSON 字段需要在 Service 层单独设置
     */
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "sessionId", ignore = true)
    @Mapping(target = "resume", ignore = true)
    @Mapping(target = "totalQuestions", ignore = true)
    @Mapping(target = "currentQuestionIndex", ignore = true)
    @Mapping(target = "questionsJson", ignore = true)
    @Mapping(target = "strengthsJson", ignore = true)
    @Mapping(target = "improvementsJson", ignore = true)
    @Mapping(target = "referenceAnswersJson", ignore = true)
    @Mapping(target = "answers", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "status", ignore = true)
    @Mapping(target = "completedAt", ignore = true)
    void updateSessionFromReport(InterviewReportDTO report, @MappingTarget InterviewSessionEntity session);

    // ========== 面试历史列表项映射 ==========

    /**
     * InterviewSessionEntity 转换为面试历史列表项 DTO
     */
    default InterviewHistoryItemDTO toInterviewHistoryItem(InterviewSessionEntity session) {
        return new InterviewHistoryItemDTO(
            session.getId(),
            session.getSessionId(),
            session.getTotalQuestions(),
            session.getStatus().toString(),
            session.getEvaluateStatus() != null ? session.getEvaluateStatus().name() : null,
            session.getEvaluateError(),
            session.getOverallScore(),
            session.getCreatedAt(),
            session.getCompletedAt()
        );
    }

    /**
     * 批量转换面试历史
     */
    default List<InterviewHistoryItemDTO> toInterviewHistoryList(List<InterviewSessionEntity> sessions) {
        return sessions.stream()
            .map(this::toInterviewHistoryItem)
            .toList();
    }

    // ========== 工具方法 ==========

    @Named("nullIndexToZero")
    default int nullIndexToZero(Integer value) {
        return value != null ? value : 0;
    }

    @Named("nullScoreToZero")
    default int nullScoreToZero(Integer value) {
        return value != null ? value : 0;
    }

    @Named("normalizeCategory")
    default String normalizeCategory(String category) {
        return InterviewQuestionDTO.normalizeCategory(category);
    }
}
