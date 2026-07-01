package interview.guide.modules.admin.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.model.AsyncTaskStatus;
import interview.guide.infrastructure.export.PdfExportService;
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
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

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

  public List<ResumeListItemDTO> listUserResumes(Long userId) {
    String ownerId = userId.toString();
    return resumeRepository.findAllByUserIdOrderByUploadedAtDesc(ownerId).stream()
        .map(this::toResumeListItem)
        .toList();
  }

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

    return new ResumeDetailDTO(
        resume.getId(),
        resume.getOriginalFilename(),
        resume.getFileSize(),
        resume.getContentType(),
        resume.getStorageUrl(),
        resume.getUploadedAt(),
        resume.getAccessCount(),
        resume.getResumeText(),
        resume.getAnalyzeStatus(),
        resume.getAnalyzeError(),
        analysisHistory,
        interviewMapper.toInterviewHistoryList(
            interviewSessionRepository.findByResumeIdOrderByCreatedAtDesc(resumeId)
        )
    );
  }

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

  public List<AdminInterviewItemDTO> listUserInterviews(Long userId) {
    String ownerId = userId.toString();
    Stream<AdminInterviewItemDTO> textItems =
        interviewSessionRepository.findAllByUserIdOrderByCreatedAtDesc(ownerId).stream()
            .map(this::toTextInterviewItem);
    Map<Long, VoiceInterviewEvaluationEntity> voiceEvaluations =
        loadVoiceEvaluations(ownerId);
    Stream<AdminInterviewItemDTO> voiceItems =
        voiceSessionRepository.findByUserIdOrderByUpdatedAtDesc(ownerId).stream()
            .map(session -> toVoiceInterviewItem(session, voiceEvaluations.get(session.getId())));

    return Stream.concat(textItems, voiceItems)
        .sorted(Comparator.comparing(AdminInterviewItemDTO::createdAt, Comparator.nullsLast(
            Comparator.reverseOrder())))
        .toList();
  }

  public InterviewDetailDTO getTextInterviewDetail(String sessionId) {
    InterviewSessionEntity session =
        interviewSessionRepository.findBySessionIdWithResume(sessionId)
            .orElseThrow(() -> new BusinessException(ErrorCode.INTERVIEW_SESSION_NOT_FOUND));
    return buildInterviewDetail(session);
  }

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

  public AdminVoiceInterviewDetailDTO getVoiceInterviewDetail(Long sessionId) {
    VoiceInterviewSessionEntity session = voiceSessionRepository.findById(sessionId)
        .orElseThrow(() -> new BusinessException(ErrorCode.VOICE_SESSION_NOT_FOUND));
    VoiceEvaluationDetailDTO evaluation = voiceEvaluationRepository.findBySessionId(sessionId)
        .map(voiceEvaluationService::buildDetailDTO)
        .orElse(null);

    return new AdminVoiceInterviewDetailDTO(
        session.getId(),
        session.getRoleType(),
        session.getSkillId(),
        session.getDifficulty(),
        session.getStatus() != null ? session.getStatus().name() : null,
        session.getEvaluateStatus() != null ? session.getEvaluateStatus().name() : null,
        session.getEvaluateError(),
        session.getStartTime(),
        session.getEndTime(),
        session.getUpdatedAt(),
        evaluation
    );
  }

  private ResumeListItemDTO toResumeListItem(ResumeEntity resume) {
    ResumeAnalysisEntity latest =
        resumeAnalysisRepository.findFirstByResumeIdOrderByAnalyzedAtDesc(resume.getId());
    Integer latestScore = latest != null ? latest.getOverallScore() : null;
    LocalDateTime lastAnalyzedAt = latest != null ? latest.getAnalyzedAt() : null;
    int interviewCount = interviewSessionRepository.findByResumeIdOrderByCreatedAtDesc(resume.getId())
        .size();

    return new ResumeListItemDTO(
        resume.getId(),
        resume.getOriginalFilename(),
        resume.getFileSize(),
        resume.getUploadedAt(),
        resume.getAccessCount(),
        latestScore,
        lastAnalyzedAt,
        interviewCount,
        resume.getAnalyzeStatus(),
        resume.getAnalyzeError()
    );
  }

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
        buildAnswerDetailList(allQuestions, session.getAnswers())
    );
  }

  private List<InterviewDetailDTO.AnswerDetailDTO> buildAnswerDetailList(
      List<InterviewQuestionDTO> allQuestions,
      List<InterviewAnswerEntity> answers
  ) {
    List<InterviewAnswerEntity> safeAnswers = answers != null ? answers : List.of();
    if (allQuestions == null || allQuestions.isEmpty()) {
      return interviewMapper.toAnswerDetailDTOList(safeAnswers, this::extractKeyPoints);
    }

    Map<Integer, InterviewAnswerEntity> answerMap = safeAnswers.stream()
        .collect(java.util.stream.Collectors.toMap(
            InterviewAnswerEntity::getQuestionIndex,
            answer -> answer,
            (first, second) -> first
        ));

    return allQuestions.stream()
        .map(question -> {
          InterviewAnswerEntity answer = answerMap.get(question.questionIndex());
          if (answer != null) {
            return interviewMapper.toAnswerDetailDTO(answer, extractKeyPoints(answer));
          }
          return new InterviewDetailDTO.AnswerDetailDTO(
              question.questionIndex(),
              question.question(),
              question.category(),
              null,
              question.score() != null ? question.score() : 0,
              question.feedback(),
              null,
              null,
              null
          );
        })
        .toList();
  }

  private ResumeEntity findResume(Long resumeId) {
    return resumeRepository.findById(resumeId)
        .orElseThrow(() -> new BusinessException(ErrorCode.RESUME_NOT_FOUND));
  }

  private AdminInterviewItemDTO toTextInterviewItem(InterviewSessionEntity session) {
    return new AdminInterviewItemDTO(
        "TEXT",
        session.getId(),
        session.getSessionId(),
        session.getSkillId(),
        session.getDifficulty(),
        session.getTotalQuestions(),
        session.getOverallScore(),
        session.getStatus() != null ? session.getStatus().name() : null,
        session.getEvaluateStatus() != null ? session.getEvaluateStatus().name() : null,
        session.getEvaluateError(),
        session.getCreatedAt(),
        session.getCompletedAt()
    );
  }

  private AdminInterviewItemDTO toVoiceInterviewItem(
      VoiceInterviewSessionEntity session,
      VoiceInterviewEvaluationEntity evaluation
  ) {
    return new AdminInterviewItemDTO(
        "VOICE",
        session.getId(),
        session.getId().toString(),
        session.getSkillId(),
        session.getDifficulty(),
        null,
        evaluation != null ? evaluation.getOverallScore() : null,
        session.getStatus() != null ? session.getStatus().name() : null,
        session.getEvaluateStatus() != null ? session.getEvaluateStatus().name() : null,
        session.getEvaluateError(),
        session.getCreatedAt(),
        session.getEndTime()
    );
  }

  private Map<Long, VoiceInterviewEvaluationEntity> loadVoiceEvaluations(String ownerId) {
    List<Long> sessionIds = voiceSessionRepository.findByUserIdOrderByUpdatedAtDesc(ownerId).stream()
        .map(VoiceInterviewSessionEntity::getId)
        .toList();
    if (sessionIds.isEmpty()) {
      return Map.of();
    }
    return voiceEvaluationRepository.findBySessionIdIn(sessionIds).stream()
        .collect(java.util.stream.Collectors.toMap(
            VoiceInterviewEvaluationEntity::getSessionId,
            evaluation -> evaluation,
            (first, second) -> first
        ));
  }

  private List<String> extractStrengths(ResumeAnalysisEntity entity) {
    return parseJson(entity.getStrengthsJson(), new TypeReference<>() {});
  }

  private List<Object> extractSuggestions(ResumeAnalysisEntity entity) {
    return parseJson(entity.getSuggestionsJson(), new TypeReference<>() {});
  }

  private List<String> extractKeyPoints(InterviewAnswerEntity answer) {
    return parseJson(answer.getKeyPointsJson(), new TypeReference<>() {});
  }

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

  public record ExportResult(byte[] pdfBytes, String filename) {
  }
}
