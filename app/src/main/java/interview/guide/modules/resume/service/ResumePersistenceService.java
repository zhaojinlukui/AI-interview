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

/**
 * 简历持久化服务
 * 负责简历及其分析结果的存储、查询、删除等操作
 * 包括重复检测、数据转换和事务管理等功能
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResumePersistenceService {

    private final ResumeRepository resumeRepository;
    private final ResumeAnalysisRepository analysisRepository;
    private final ObjectMapper objectMapper;
    private final ResumeMapper resumeMapper;
    private final FileHashService fileHashService;

    // 查找是否已存在相同的简历文件
    public Optional<ResumeEntity> findExistingResume(MultipartFile file) {
        try {
            String userId = CurrentUserContext.getRequiredUserId();
            String fileHash = fileHashService.calculateHash(file);  // 获取哈希值
            Optional<ResumeEntity> existing = resumeRepository.findByUserIdAndFileHash(userId, fileHash);

            if (existing.isPresent()) {
                log.info("检测到重复简历：userId={}, hash={}", userId, fileHash);
            }
            return existing;
        } catch (Exception e) {
            log.error("检查简历重复时出错", e);
            return Optional.empty();
        }
    }

    // 保存简历信息到数据库
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
            log.info("简历保存成功：id={}, userId={}, hash={}",
                    saved.getId(), saved.getUserId(), fileHash);
            return saved;
        } catch (Exception e) {
            log.error("保存简历失败：{}", e.getMessage(), e);
            throw new BusinessException(ErrorCode.RESUME_UPLOAD_FAILED, "Failed to save resume");
        }
    }

    // 保存简历分析结果
    @Transactional(rollbackFor = Exception.class)
    public ResumeAnalysisEntity saveAnalysis(ResumeEntity resume, ResumeAnalysisResponse analysis) {
        try {
            ResumeAnalysisEntity entity = resumeMapper.toAnalysisEntity(analysis);
            entity.setResume(resume);
            entity.setOverallScore(analysis.overallScore());
            entity.setStrengthsJson(objectMapper.writeValueAsString(analysis.strengths()));
            entity.setSuggestionsJson(objectMapper.writeValueAsString(analysis.suggestions()));

            ResumeAnalysisEntity saved = analysisRepository.save(entity);
            log.info("简历分析结果保存成功：analysisId={}, resumeId={}, score={}",
                    saved.getId(), resume.getId(), saved.getOverallScore());
            return saved;
        } catch (JacksonException e) {
            log.error("序列化简历分析结果失败：{}", e.getMessage(), e);
            throw new BusinessException(ErrorCode.RESUME_ANALYSIS_FAILED, "Failed to save analysis result");
        }
    }

    // 获取指定简历的最新分析结果（实体形式）
    public Optional<ResumeAnalysisEntity> getLatestAnalysis(Long resumeId) {
        return Optional.ofNullable(analysisRepository.findFirstByResumeIdOrderByAnalyzedAtDesc(resumeId));
    }

    // 获取指定简历的最新分析结果（DTO形式）
    public Optional<ResumeAnalysisResponse> getLatestAnalysisAsDTO(Long resumeId) {
        return getLatestAnalysis(resumeId).map(this::entityToDTO);
    }

    // 查询当前用户的所有简历
    public List<ResumeEntity> findAllResumes() {
        return resumeRepository.findAllByUserIdOrderByUploadedAtDesc(
                CurrentUserContext.getRequiredUserId());
    }

    // 查询指定简历的所有分析记录
    public List<ResumeAnalysisEntity> findAnalysesByResumeId(Long resumeId) {
        return analysisRepository.findByResumeIdOrderByAnalyzedAtDesc(resumeId);
    }

    // 将分析实体转换为响应DTO
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
            log.error("反序列化简历分析结果失败：{}", e.getMessage(), e);
            throw new BusinessException(ErrorCode.RESUME_ANALYSIS_FAILED, "Failed to load analysis result");
        }
    }

    // 根据ID查询简历（带权限验证）
    public Optional<ResumeEntity> findById(Long id) {
        return resumeRepository.findByIdAndUserId(id, CurrentUserContext.getRequiredUserId());
    }

    // 删除简历及其所有分析记录
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
            log.info("已删除{}条简历分析记录", analyses.size());
        }

        resumeRepository.delete(resume);
        log.info("简历删除成功：id={}, filename={}", id, resume.getOriginalFilename());
    }
}
