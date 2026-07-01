package interview.guide.modules.resume.service;

import interview.guide.common.auth.CurrentUserContext;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.infrastructure.file.FileHashService;
import interview.guide.infrastructure.mapper.ResumeMapper;
import interview.guide.modules.interview.model.ResumeAnalysisResponse;
import interview.guide.modules.resume.model.ResumeAnalysisEntity;
import interview.guide.modules.resume.model.ResumeEntity;
import interview.guide.modules.resume.repository.ResumeAnalysisRepository;
import interview.guide.modules.resume.repository.ResumeRepository;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Slf4j
@Service
@RequiredArgsConstructor
public class ResumePersistenceService {

    private final ResumeRepository resumeRepository;
    private final ResumeAnalysisRepository analysisRepository;
    private final ObjectMapper objectMapper;
    private final ResumeMapper resumeMapper;
    private final FileHashService fileHashService;

    public Optional<ResumeEntity> findExistingResume(MultipartFile file) {
        try {
            String userId = CurrentUserContext.getRequiredUserId();
            String fileHash = fileHashService.calculateHash(file);
            Optional<ResumeEntity> existing = resumeRepository.findByUserIdAndFileHash(userId, fileHash);

            if (existing.isPresent()) {
                log.info("Detected duplicate resume: userId={}, hash={}", userId, fileHash);
                ResumeEntity resume = existing.get();
                resume.incrementAccessCount();
                resumeRepository.save(resume);
            }

            return existing;
        } catch (Exception e) {
            log.error("Error while checking resume duplicate", e);
            return Optional.empty();
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public ResumeEntity saveResume(MultipartFile file, String resumeText,
                                   String storageKey, String storageUrl) {
        try {
            String fileHash = fileHashService.calculateHash(file);

            ResumeEntity resume = new ResumeEntity();
            resume.setFileHash(fileHash);
            resume.setUserId(CurrentUserContext.getRequiredUserId());
            resume.setOriginalFilename(file.getOriginalFilename());
            resume.setFileSize(file.getSize());
            resume.setContentType(file.getContentType());
            resume.setStorageKey(storageKey);
            resume.setStorageUrl(storageUrl);
            resume.setResumeText(resumeText);

            ResumeEntity saved = resumeRepository.save(resume);
            log.info("Resume saved: id={}, userId={}, hash={}",
                    saved.getId(), saved.getUserId(), fileHash);
            return saved;
        } catch (Exception e) {
            log.error("Failed to save resume: {}", e.getMessage(), e);
            throw new BusinessException(ErrorCode.RESUME_UPLOAD_FAILED, "Failed to save resume");
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public ResumeAnalysisEntity saveAnalysis(ResumeEntity resume, ResumeAnalysisResponse analysis) {
        try {
            ResumeAnalysisEntity entity = resumeMapper.toAnalysisEntity(analysis);
            entity.setResume(resume);
            entity.setOverallScore(analysis.overallScore());
            entity.setStrengthsJson(objectMapper.writeValueAsString(analysis.strengths()));
            entity.setSuggestionsJson(objectMapper.writeValueAsString(analysis.suggestions()));

            ResumeAnalysisEntity saved = analysisRepository.save(entity);
            log.info("Resume analysis saved: analysisId={}, resumeId={}, score={}",
                    saved.getId(), resume.getId(), saved.getOverallScore());
            return saved;
        } catch (JacksonException e) {
            log.error("Failed to serialize resume analysis result: {}", e.getMessage(), e);
            throw new BusinessException(ErrorCode.RESUME_ANALYSIS_FAILED, "Failed to save analysis result");
        }
    }

    public Optional<ResumeAnalysisEntity> getLatestAnalysis(Long resumeId) {
        return Optional.ofNullable(analysisRepository.findFirstByResumeIdOrderByAnalyzedAtDesc(resumeId));
    }

    public Optional<ResumeAnalysisResponse> getLatestAnalysisAsDTO(Long resumeId) {
        return getLatestAnalysis(resumeId).map(this::entityToDTO);
    }

    public List<ResumeEntity> findAllResumes() {
        return resumeRepository.findAllByUserIdOrderByUploadedAtDesc(
                CurrentUserContext.getRequiredUserId());
    }

    public List<ResumeAnalysisEntity> findAnalysesByResumeId(Long resumeId) {
        return analysisRepository.findByResumeIdOrderByAnalyzedAtDesc(resumeId);
    }

    public ResumeAnalysisResponse entityToDTO(ResumeAnalysisEntity entity) {
        try {
            List<String> strengths = objectMapper.readValue(
                    entity.getStrengthsJson() != null ? entity.getStrengthsJson() : "[]",
                    new TypeReference<>() {
                    }
            );

            List<ResumeAnalysisResponse.Suggestion> suggestions = objectMapper.readValue(
                    entity.getSuggestionsJson() != null ? entity.getSuggestionsJson() : "[]",
                    new TypeReference<>() {
                    }
            );

            return new ResumeAnalysisResponse(
                    entity.getOverallScore(),
                    resumeMapper.toScoreDetail(entity),
                    entity.getSummary(),
                    strengths,
                    suggestions,
                    entity.getResume().getResumeText()
            );
        } catch (JacksonException e) {
            log.error("Failed to deserialize resume analysis result: {}", e.getMessage(), e);
            throw new BusinessException(ErrorCode.RESUME_ANALYSIS_FAILED, "Failed to load analysis result");
        }
    }

    public Optional<ResumeEntity> findById(Long id) {
        return resumeRepository.findByIdAndUserId(id, CurrentUserContext.getRequiredUserId());
    }

    @Transactional(rollbackFor = Exception.class)
    public void deleteResume(Long id) {
        Optional<ResumeEntity> resumeOpt = resumeRepository.findById(id);
        if (resumeOpt.isEmpty()) {
            throw new BusinessException(ErrorCode.RESUME_NOT_FOUND);
        }

        ResumeEntity resume = resumeOpt.get();

        List<ResumeAnalysisEntity> analyses = analysisRepository.findByResumeIdOrderByAnalyzedAtDesc(id);
        if (!analyses.isEmpty()) {
            analysisRepository.deleteAll(analyses);
            log.info("Deleted {} resume analysis records", analyses.size());
        }

        resumeRepository.delete(resume);
        log.info("Resume deleted: id={}, filename={}", id, resume.getOriginalFilename());
    }
}
