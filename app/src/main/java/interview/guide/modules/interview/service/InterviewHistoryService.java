package interview.guide.modules.interview.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.infrastructure.export.PdfExportService;
import interview.guide.infrastructure.mapper.InterviewMapper;
import interview.guide.modules.interview.model.InterviewAnswerEntity;
import interview.guide.modules.interview.model.InterviewDetailDTO;
import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.interview.model.InterviewSessionEntity;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * 面试历史记录服务
 * 负责查询面试会话详情以及导出面试报告PDF
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InterviewHistoryService {

    private final InterviewPersistenceService interviewPersistenceService;
    private final PdfExportService pdfExportService;
    private final ObjectMapper objectMapper;
    private final InterviewMapper interviewMapper;

    /**
     * 获取面试会话详情
     * 从数据库中查询指定会话，解析JSON字段并组装为完整的详情DTO
     */
    public InterviewDetailDTO getInterviewDetail(String sessionId) {
        // 查询当前用户的面试会话，若不存在则抛出业务异常
        Optional<InterviewSessionEntity> sessionOpt =
                interviewPersistenceService.findBySessionIdForCurrentUser(sessionId);
        if (sessionOpt.isEmpty()) {
            throw new BusinessException(ErrorCode.INTERVIEW_SESSION_NOT_FOUND);
        }

        InterviewSessionEntity session = sessionOpt.get();
        // 解析会话中存储的各类JSON字段
        List<Object> questions = parseJson(session.getQuestionsJson(), new TypeReference<>() {});
        List<String> strengths = parseJson(session.getStrengthsJson(), new TypeReference<>() {});
        List<String> improvements = parseJson(session.getImprovementsJson(), new TypeReference<>() {});
        List<Object> referenceAnswers = parseJson(session.getReferenceAnswersJson(), new TypeReference<>() {});
        List<InterviewQuestionDTO> allQuestions = parseJson(
                session.getQuestionsJson(),
                new TypeReference<>() {}
        );

        // 将会话实体和解析后的数据组装为详情DTO并返回
        return interviewMapper.toDetailDTO(
                session,
                questions,
                strengths,
                improvements,
                referenceAnswers,
                interviewMapper.toAnswerDetailDTOList(
                        allQuestions,
                        session.getAnswers(),
                        this::extractKeyPoints
                )
        );
    }

    /**
     * 提取回答中的关键点
     * 将回答实体中存储的关键点JSON解析为字符串列表
     */
    private List<String> extractKeyPoints(InterviewAnswerEntity answer) {
        return parseJson(answer.getKeyPointsJson(), new TypeReference<>() {});
    }

    /**
     * 安全解析JSON字符串
     * 将JSON字符串反序列化为指定类型，若字符串为空或解析失败则返回null
     */
    private <T> T parseJson(String json, TypeReference<T> typeRef) {
        // JSON字符串为空时直接返回null
        if (json == null) {
            return null;
        }
        try {
            // 使用Jackson将JSON字符串反序列化为目标类型
            return objectMapper.readValue(json, typeRef);
        } catch (JacksonException e) {
            // 解析失败时记录错误日志并返回null，避免中断主流程
            log.error("解析面试历史 JSON 失败", e);
            return null;
        }
    }

    /**
     * 导出面试报告PDF
     * 根据会话ID生成面试报告的PDF文件并返回字节数组
     */
    public byte[] exportInterviewPdf(String sessionId) {
        // 查询当前用户的面试会话，若不存在则抛出业务异常
        Optional<InterviewSessionEntity> sessionOpt =
                interviewPersistenceService.findBySessionIdForCurrentUser(sessionId);
        if (sessionOpt.isEmpty()) {
            throw new BusinessException(ErrorCode.INTERVIEW_SESSION_NOT_FOUND);
        }

        InterviewSessionEntity session = sessionOpt.get();
        try {
            // 调用PDF导出服务生成面试报告
            return pdfExportService.exportInterviewReport(session);
        } catch (Exception e) {
            // 导出失败时记录日志并抛出业务异常
            log.error("导出面试 PDF 失败: sessionId={}", sessionId, e);
            throw new BusinessException(ErrorCode.EXPORT_PDF_FAILED, "导出PDF失败: " + e.getMessage());
        }
    }
}