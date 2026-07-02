package interview.guide.modules.admin.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.infrastructure.export.PdfExportService;
import interview.guide.infrastructure.mapper.AdminReportMapper;
import interview.guide.infrastructure.mapper.InterviewMapper;
import interview.guide.infrastructure.mapper.ResumeMapper;
import interview.guide.modules.admin.model.AdminInterviewItemDTO;
import interview.guide.modules.admin.model.AdminVoiceInterviewDetailDTO;
import interview.guide.modules.interview.model.InterviewAnswerEntity;
import interview.guide.modules.interview.model.InterviewDetailDTO;
import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.interview.model.InterviewSessionEntity;
import interview.guide.modules.interview.model.ResumeAnalysisResponse;
import interview.guide.modules.interview.repository.InterviewSessionRepository;
import interview.guide.modules.resume.model.ResumeAnalysisEntity;
import interview.guide.modules.resume.model.ResumeDetailDTO;
import interview.guide.modules.resume.model.ResumeEntity;
import interview.guide.modules.resume.model.ResumeListItemDTO;
import interview.guide.modules.resume.repository.ResumeAnalysisRepository;
import interview.guide.modules.resume.repository.ResumeRepository;
import interview.guide.modules.resume.service.ResumePersistenceService;
import interview.guide.modules.voiceinterview.dto.VoiceEvaluationDetailDTO;
import interview.guide.modules.voiceinterview.model.VoiceInterviewEvaluationEntity;
import interview.guide.modules.voiceinterview.model.VoiceInterviewSessionEntity;
import interview.guide.modules.voiceinterview.repository.VoiceInterviewEvaluationRepository;
import interview.guide.modules.voiceinterview.repository.VoiceInterviewSessionRepository;
import interview.guide.modules.voiceinterview.service.VoiceInterviewEvaluationService;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * 管理员报表服务
 * 提供管理员视角下的简历管理、面试记录查询和 PDF 导出功能
 * 整合了文本面试和语音面试两种模式的统一视图
 * 相比之前版本，将部分转换逻辑委托给了专门的 Mapper 类，使 Service 层更聚焦于业务编排
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminReportService {

    private final ResumeRepository resumeRepository;
    private final ResumeAnalysisRepository resumeAnalysisRepository;
    private final InterviewSessionRepository interviewSessionRepository;
    private final VoiceInterviewSessionRepository voiceSessionRepository;
    private final VoiceInterviewEvaluationRepository voiceEvaluationRepository;
    private final ResumePersistenceService resumePersistenceService;
    private final VoiceInterviewEvaluationService voiceEvaluationService;
    private final PdfExportService pdfExportService;
    private final ObjectMapper objectMapper;
    private final ResumeMapper resumeMapper;
    private final InterviewMapper interviewMapper;
    private final AdminReportMapper adminReportMapper;

    /**
     * 获取指定用户的所有简历列表，按上传时间倒序排列
     */
    public List<ResumeListItemDTO> listUserResumes(Long userId) {
        String ownerId = userId.toString();
        return resumeRepository.findAllByUserIdOrderByUploadedAtDesc(ownerId).stream()
                .map(this::toResumeListItem)
                .toList();
    }

    /**
     * 获取简历的详细信息，包含分析历史和关联的面试记录
     * 使用 ResumeMapper 构建详情 DTO，将分析记录和面试历史统一组装
     */
    public ResumeDetailDTO getResumeDetail(Long resumeId) {
        ResumeEntity resume = findResume(resumeId);
        List<ResumeAnalysisEntity> analyses =
                resumeAnalysisRepository.findByResumeIdOrderByAnalyzedAtDesc(resumeId);
        List<ResumeDetailDTO.AnalysisHistoryDTO> analysisHistory =
                resumeMapper.toAnalysisHistoryDTOList(
                        analyses,
                        this::extractStrengths,
                        this::extractSuggestions
                );

        return resumeMapper.toDetailDTO(
                resume,
                analysisHistory,
                interviewMapper.toInterviewHistoryList(
                        interviewSessionRepository.findByResumeIdOrderByCreatedAtDesc(resumeId)
                )
        );
    }

    /**
     * 导出简历分析结果为 PDF 文件
     * 使用最新的分析结果生成 PDF，文件名包含简历 ID 便于识别
     */
    public ExportResult exportResumeAnalysisPdf(Long resumeId) {
        ResumeEntity resume = findResume(resumeId);
        ResumeAnalysisEntity latest = Optional
                .ofNullable(resumeAnalysisRepository.findFirstByResumeIdOrderByAnalyzedAtDesc(resumeId))
                .orElseThrow(() -> new BusinessException(ErrorCode.RESUME_ANALYSIS_NOT_FOUND));
        ResumeAnalysisResponse analysis = resumePersistenceService.entityToDTO(latest);

        try {
            byte[] pdfBytes = pdfExportService.exportResumeAnalysis(resume, analysis);
            return new ExportResult(pdfBytes, "resume-analysis-" + resumeId + ".pdf");
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("Admin resume PDF export failed: resumeId={}", resumeId, e);
            throw new BusinessException(ErrorCode.EXPORT_PDF_FAILED, "Resume PDF export failed");
        }
    }

    /**
     * 获取用户的所有面试记录，合并文本面试和语音面试并按创建时间倒序排列
     * 使用 AdminReportMapper 进行实体到 DTO 的转换，统一两种面试模式的数据结构
     */
    public List<AdminInterviewItemDTO> listUserInterviews(Long userId) {
        String ownerId = userId.toString();
        Stream<AdminInterviewItemDTO> textItems =
                interviewSessionRepository.findAllByUserIdOrderByCreatedAtDesc(ownerId).stream()
                        .map(adminReportMapper::toTextInterviewItem);

        List<VoiceInterviewSessionEntity> voiceSessions =
                voiceSessionRepository.findByUserIdOrderByUpdatedAtDesc(ownerId);
        Map<Long, VoiceInterviewEvaluationEntity> voiceEvaluations =
                loadVoiceEvaluations(voiceSessions);
        Stream<AdminInterviewItemDTO> voiceItems = voiceSessions.stream()
                .map(session -> adminReportMapper.toVoiceInterviewItem(
                        session,
                        voiceEvaluations.get(session.getId())
                ));

        return Stream.concat(textItems, voiceItems)
                .sorted(Comparator.comparing(
                        AdminInterviewItemDTO::createdAt,
                        Comparator.nullsLast(Comparator.reverseOrder())
                ))
                .toList();
    }

    /**
     * 获取文本面试的详细信息
     * 包含关联的简历数据，通过 sessionId 定位唯一会话
     */
    public InterviewDetailDTO getTextInterviewDetail(String sessionId) {
        InterviewSessionEntity session =
                interviewSessionRepository.findBySessionIdWithResume(sessionId)
                        .orElseThrow(() -> new BusinessException(ErrorCode.INTERVIEW_SESSION_NOT_FOUND));
        return buildInterviewDetail(session);
    }

    /**
     * 导出文本面试报告为 PDF 文件
     * 文件名包含 sessionId 便于区分不同面试记录
     */
    public ExportResult exportTextInterviewPdf(String sessionId) {
        InterviewSessionEntity session = interviewSessionRepository.findBySessionIdWithResume(sessionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.INTERVIEW_SESSION_NOT_FOUND));
        try {
            byte[] pdfBytes = pdfExportService.exportInterviewReport(session);
            return new ExportResult(pdfBytes, "interview-report-" + sessionId + ".pdf");
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("Admin text interview PDF export failed: sessionId={}", sessionId, e);
            throw new BusinessException(ErrorCode.EXPORT_PDF_FAILED, "Interview PDF export failed");
        }
    }

    /**
     * 获取语音面试的详细信息，包含评估结果
     * 使用 AdminReportMapper 统一构建详情 DTO，评估结果可能为空
     */
    public AdminVoiceInterviewDetailDTO getVoiceInterviewDetail(Long sessionId) {
        VoiceInterviewSessionEntity session = voiceSessionRepository.findById(sessionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.VOICE_SESSION_NOT_FOUND));
        VoiceEvaluationDetailDTO evaluation = voiceEvaluationRepository.findBySessionId(sessionId)
                .map(voiceEvaluationService::buildDetailDTO)
                .orElse(null);

        return adminReportMapper.toVoiceInterviewDetail(session, evaluation);
    }

    /**
     * 将简历实体转换为列表项 DTO
     * 补充最新分析评分、最近分析时间和关联面试数量等摘要信息，委托给 ResumeMapper 完成转换
     */
    private ResumeListItemDTO toResumeListItem(ResumeEntity resume) {
        ResumeAnalysisEntity latest =
                resumeAnalysisRepository.findFirstByResumeIdOrderByAnalyzedAtDesc(resume.getId());
        Integer latestScore = latest != null ? latest.getOverallScore() : null;
        LocalDateTime lastAnalyzedAt = latest != null ? latest.getAnalyzedAt() : null;
        int interviewCount = interviewSessionRepository.findByResumeIdOrderByCreatedAtDesc(resume.getId())
                .size();

        return resumeMapper.toListItemDTO(resume, latestScore, lastAnalyzedAt, interviewCount);
    }

    /**
     * 构建面试详情 DTO
     * 解析 JSON 格式的各类字段，并使用 InterviewMapper 将问题和答案统一组装为详情对象
     * 将问题与答案的匹配逻辑委托给 InterviewMapper，简化 Service 层代码
     */
    private InterviewDetailDTO buildInterviewDetail(InterviewSessionEntity session) {
        List<Object> questions = parseJson(session.getQuestionsJson(), new TypeReference<>() {});
        List<String> strengths = parseJson(session.getStrengthsJson(), new TypeReference<>() {});
        List<String> improvements = parseJson(session.getImprovementsJson(), new TypeReference<>() {});
        List<Object> referenceAnswers =
                parseJson(session.getReferenceAnswersJson(), new TypeReference<>() {});
        List<InterviewQuestionDTO> allQuestions =
                parseJson(session.getQuestionsJson(), new TypeReference<>() {});

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
     * 根据 ID 查找简历，不存在时抛出业务异常
     */
    private ResumeEntity findResume(Long resumeId) {
        return resumeRepository.findById(resumeId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESUME_NOT_FOUND));
    }

    /**
     * 批量加载指定会话列表的语音面试评估结果
     * 接收会话列表作为参数，提取所有会话 ID 后一次性查询对应的评估记录，避免 N+1 查询问题
     * 当会话列表为空时直接返回空 Map，减少不必要的数据库查询
     */
    private Map<Long, VoiceInterviewEvaluationEntity> loadVoiceEvaluations(
            List<VoiceInterviewSessionEntity> sessions
    ) {
        List<Long> sessionIds = sessions.stream()
                .map(VoiceInterviewSessionEntity::getId)
                .toList();
        if (sessionIds.isEmpty()) {
            return Map.of();
        }
        return voiceEvaluationRepository.findBySessionIdIn(sessionIds).stream()
                .collect(Collectors.toMap(
                        VoiceInterviewEvaluationEntity::getSessionId,
                        evaluation -> evaluation,
                        (first, second) -> first
                ));
    }

    /**
     * 从简历分析实体中提取优势列表
     * 解析 JSON 字段，解析失败返回 null
     */
    private List<String> extractStrengths(ResumeAnalysisEntity entity) {
        return parseJson(entity.getStrengthsJson(), new TypeReference<>() {});
    }

    /**
     * 从简历分析实体中提取改进建议列表
     * 解析 JSON 字段，解析失败返回 null
     */
    private List<Object> extractSuggestions(ResumeAnalysisEntity entity) {
        return parseJson(entity.getSuggestionsJson(), new TypeReference<>() {});
    }

    /**
     * 从面试答案实体中提取关键点列表
     * 解析 JSON 字段，解析失败返回 null
     */
    private List<String> extractKeyPoints(InterviewAnswerEntity answer) {
        return parseJson(answer.getKeyPointsJson(), new TypeReference<>() {});
    }

    /**
     * 通用的 JSON 解析方法
     * 处理空值和解析异常，解析失败时记录日志并返回 null
     */
    private <T> T parseJson(String json, TypeReference<T> typeRef) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, typeRef);
        } catch (JacksonException e) {
            log.error("Admin report JSON parse failed", e);
            return null;
        }
    }

    /**
     * PDF 导出结果记录
     * 包含 PDF 文件的字节数组和建议的文件名
     */
    public record ExportResult(byte[] pdfBytes, String filename) {
    }
}