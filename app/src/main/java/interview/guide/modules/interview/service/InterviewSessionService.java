package interview.guide.modules.interview.service;

import interview.guide.common.auth.CurrentUserContext;
import interview.guide.common.constant.CommonConstants.InterviewDefaults;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.model.AsyncTaskStatus;
import interview.guide.infrastructure.redis.InterviewSessionCache;
import interview.guide.infrastructure.redis.InterviewSessionCache.CachedSession;
import interview.guide.modules.interview.listener.EvaluateStreamProducer;
import interview.guide.modules.interview.model.CreateInterviewRequest;
import interview.guide.modules.interview.model.HistoricalQuestion;
import interview.guide.modules.interview.model.InterviewAnswerEntity;
import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.interview.model.InterviewSessionDTO;
import interview.guide.modules.interview.model.InterviewSessionEntity;
import interview.guide.modules.interview.model.SubmitAnswerRequest;
import interview.guide.modules.interview.model.SubmitAnswerResponse;
import interview.guide.modules.interview.model.InterviewSessionDTO.SessionStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 文字模拟面试会话服务
 *
 * <p>负责创建面试会话、缓存会话状态、恢复未完成会话、提交回答、
 * 完成面试并投递异步评估任务</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InterviewSessionService {

    private final InterviewQuestionService questionService;
    private final InterviewPersistenceService persistenceService;
    private final InterviewSessionCache sessionCache;
    private final ObjectMapper objectMapper;
    private final EvaluateStreamProducer evaluateStreamProducer;

    /**
     * 创建文字模拟面试会话
     * 同一份简历默认复用未完成会话
     */
    public InterviewSessionDTO createSession(CreateInterviewRequest request) {
        String userId = CurrentUserContext.getRequiredUserId();
        // 查看是否存在未完成的带简历会话
        if (request.resumeId() != null && !Boolean.TRUE.equals(request.forceCreate())) {
            Optional<InterviewSessionDTO> unfinishedOpt = findUnfinishedSession(request.resumeId());
            if (unfinishedOpt.isPresent()) {
                log.info("发现未完成面试会话，复用会话: resumeId={}, sessionId={}",
                    request.resumeId(), unfinishedOpt.get().sessionId());
                return unfinishedOpt.get();
            }
        }

        // 生成 16 位短会话 ID
        String sessionId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        String skillId = request.skillId() != null ? request.skillId() : InterviewDefaults.SKILL_ID;
        String difficulty = request.difficulty() != null ? request.difficulty() : InterviewDefaults.DIFFICULTY;
        log.info("创建文字面试会话: sessionId={}, skillId={}, difficulty={}, questionCount={}, resumeId={}",
            sessionId, skillId, difficulty, request.questionCount(), request.resumeId());

        // 查询历史题目
        List<HistoricalQuestion> historicalQuestions = persistenceService.getHistoricalQuestions(skillId, request.resumeId());

        // 根据面试方向、难度、历史题目和可选 JD 生成问题
        List<InterviewQuestionDTO> questions = questionService.generateQuestionsBySkill(
            skillId,
            difficulty,
            request.resumeText(),
            request.questionCount(),
            historicalQuestions,
            request.customCategories(),
            request.jdText()
        );

        // 新会话先写入 Redis 缓存
        sessionCache.saveSession(
            sessionId,
            userId,
            request.resumeText() != null ? request.resumeText() : "",
            request.resumeId(),
            questions,
            0,
            SessionStatus.CREATED
        );

        // 再持久化到数据库，持久化失败不阻塞本次会话创建
        try {
            persistenceService.saveSession(sessionId, request.resumeId(),
                questions.size(), questions, skillId, difficulty);
        } catch (Exception e) {
            log.warn("保存面试会话到数据库失败: {}", e.getMessage());
        }

        return new InterviewSessionDTO(
            sessionId,
            request.resumeText() != null ? request.resumeText() : "",
            questions.size(),
            0,
            questions,
            SessionStatus.CREATED
        );
    }

    /**
     * 使用固定题目创建错题重练文字面试场次
     */
    public InterviewSessionDTO createMistakeReviewSession(
        String resumeText,
        Long resumeId,
        List<InterviewQuestionDTO> questions,
        String skillId,
        String difficulty,
        InterviewSessionEntity.MistakeSourceType sourceType,
        String sourceSessionId
    ) {
        if (questions == null || questions.isEmpty()) {
            throw new BusinessException(ErrorCode.INTERVIEW_QUESTION_NOT_FOUND, "暂无可重练的错题");
        }

        String sessionId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        String effectiveResumeText = resumeText != null ? resumeText : "";
        String effectiveSkillId = skillId != null ? skillId : InterviewDefaults.SKILL_ID;
        String effectiveDifficulty = difficulty != null ? difficulty : InterviewDefaults.DIFFICULTY;

        sessionCache.saveSession(
            sessionId,
            CurrentUserContext.getRequiredUserId(),
            effectiveResumeText,
            resumeId,
            questions,
            0,
            SessionStatus.CREATED,
            false
        );

        try {
            persistenceService.saveSession(
                sessionId,
                resumeId,
                questions.size(),
                questions,
                effectiveSkillId,
                effectiveDifficulty,
                InterviewSessionEntity.PracticeType.MISTAKE_REVIEW,
                sourceType,
                sourceSessionId
            );
        } catch (Exception e) {
            log.warn("保存错题重练场次到数据库失败: sessionId={}, error={}", sessionId, e.getMessage());
        }

        log.info("错题重练场次已创建: sessionId={}, sourceType={}, sourceSessionId={}, questionCount={}",
            sessionId, sourceType, sourceSessionId, questions.size());

        return new InterviewSessionDTO(
            sessionId,
            effectiveResumeText,
            questions.size(),
            0,
            questions,
            SessionStatus.CREATED
        );
    }

    // 获取面试会话
    public InterviewSessionDTO getSession(String sessionId) {
        // 1. 优先读取 Redis 缓存
        Optional<CachedSession> cachedOpt = sessionCache.getSession(sessionId);
        if (cachedOpt.isPresent()) {
            ensureCurrentUserOwns(cachedOpt.get());
            return toDTO(cachedOpt.get());
        }

        // 2. 缓存未命中时从数据库恢复会话
        CachedSession restoredSession = restoreSessionFromDatabase(sessionId);
        if (restoredSession == null) {
            throw new BusinessException(ErrorCode.INTERVIEW_SESSION_NOT_FOUND);
        }

        return toDTO(restoredSession);
    }

    /**
     * 根据简历 ID 查找未完成的面试会话
     */
    public Optional<InterviewSessionDTO> findUnfinishedSession(Long resumeId) {
        try {
            // 1. 先从 Redis 查找未完成会话
            Optional<String> cachedSessionIdOpt = sessionCache.findUnfinishedSessionId(resumeId);
            if (cachedSessionIdOpt.isPresent()) {
                String sessionId = cachedSessionIdOpt.get();
                Optional<CachedSession> cachedOpt = sessionCache.getSession(sessionId);
                if (cachedOpt.isPresent()) {
                    ensureCurrentUserOwns(cachedOpt.get());
                    log.debug("从 Redis 命中未完成面试会话: resumeId={}, sessionId={}", resumeId, sessionId);
                    return Optional.of(toDTO(cachedOpt.get()));
                }
            }

            // 2. Redis 未命中时查询数据库
            Optional<InterviewSessionEntity> entityOpt = persistenceService.findUnfinishedSession(resumeId);
            if (entityOpt.isEmpty()) {
                return Optional.empty();
            }

            // 数据库命中后恢复到 Redis，方便后续继续面试
            InterviewSessionEntity entity = entityOpt.get();
            CachedSession restoredSession = restoreSessionFromEntity(entity);
            if (restoredSession != null) {
                return Optional.of(toDTO(restoredSession));
            }
        } catch (Exception e) {
            log.error("查找未完成面试会话失败: {}", e.getMessage(), e);
        }
        return Optional.empty();
    }


    /**
     * 从数据库恢复会话并写回 Redis 缓存
     */
    private CachedSession restoreSessionFromDatabase(String sessionId) {
        try {
            Optional<InterviewSessionEntity> entityOpt = persistenceService.findBySessionIdForCurrentUser(sessionId);
            return entityOpt.map(this::restoreSessionFromEntity).orElse(null);
        } catch (Exception e) {
            log.error("从数据库恢复面试会话失败: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * 根据持久化实体重建缓存会话
     */
    private CachedSession restoreSessionFromEntity(InterviewSessionEntity entity) {
        try {
            // 反序列化题目快照
            List<InterviewQuestionDTO> questions = objectMapper.readValue(
                entity.getQuestionsJson(),
                new TypeReference<>() {}
            );

            // 合并已经提交的回答
            List<InterviewAnswerEntity> answers = persistenceService.findAnswersBySessionId(entity.getSessionId());
            for (InterviewAnswerEntity answer : answers) {
                int index = answer.getQuestionIndex();
                if (index >= 0 && index < questions.size()) {
                    InterviewQuestionDTO question = questions.get(index);
                    questions.set(index, question.withAnswer(answer.getUserAnswer()));
                }
            }

            SessionStatus status = convertStatus(entity.getStatus());

            // 恢复后的会话重新写入 Redis 缓存
            sessionCache.saveSession(
                entity.getSessionId(),
                entity.getUserId(),
                entity.getResume() != null ? entity.getResume().getResumeText() : "",
                entity.getResume() != null ? entity.getResume().getId() : null,
                questions,
                entity.getCurrentQuestionIndex(),
                status
            );

            log.info("从数据库恢复面试会话到 Redis: sessionId={}, currentIndex={}, status={}",
                entity.getSessionId(), entity.getCurrentQuestionIndex(), entity.getStatus());

            return sessionCache.getSession(entity.getSessionId()).orElse(null);
        } catch (Exception e) {
            log.error("恢复面试会话缓存失败: {}", e.getMessage(), e);
            return null;
        }
    }

    private SessionStatus convertStatus(InterviewSessionEntity.SessionStatus status) {
        return switch (status) {
            case CREATED -> SessionStatus.CREATED;
            case IN_PROGRESS -> SessionStatus.IN_PROGRESS;
            case COMPLETED -> SessionStatus.COMPLETED;
            case EVALUATED -> SessionStatus.EVALUATED;
        };
    }

    /**
     * 提交面试回答并推进当前题目索引
     */
    public SubmitAnswerResponse submitAnswer(SubmitAnswerRequest request) {
        CachedSession session = getOrRestoreSession(request.sessionId());
        List<InterviewQuestionDTO> questions = session.getQuestions(objectMapper);

        // 校验题目索引
        int index = request.questionIndex();
        if (index < 0 || index >= questions.size()) {
            throw new BusinessException(ErrorCode.INTERVIEW_QUESTION_NOT_FOUND, "题目索引无效: " + index);
        }

        // 将用户回答写回题目列表
        InterviewQuestionDTO question = questions.get(index);
        InterviewQuestionDTO answeredQuestion = question.withAnswer(request.answer());
        questions.set(index, answeredQuestion);

        int newIndex = index + 1;

        boolean hasNextQuestion = newIndex < questions.size();
        InterviewQuestionDTO nextQuestion = hasNextQuestion ? questions.get(newIndex) : null;
        SessionStatus newStatus = hasNextQuestion ? SessionStatus.IN_PROGRESS : SessionStatus.COMPLETED;

        // 更新 Redis 中的会话进度
        sessionCache.updateQuestions(request.sessionId(), questions);
        sessionCache.updateCurrentIndex(request.sessionId(), newIndex);
        if (newStatus == SessionStatus.COMPLETED) {
            sessionCache.updateSessionStatus(request.sessionId(), SessionStatus.COMPLETED);
        }

        // 尽量持久化回答和进度，失败时保留缓存中的可继续状态
        try {
            persistenceService.saveAnswer(
                request.sessionId(), index,
                question.question(), question.category(),
                request.answer(), 0, null  // AI 评估分数稍后异步回填
            );
            persistenceService.updateCurrentQuestionIndex(request.sessionId(), newIndex);
            persistenceService.updateSessionStatus(request.sessionId(),
                newStatus == SessionStatus.COMPLETED
                    ? InterviewSessionEntity.SessionStatus.COMPLETED
                    : InterviewSessionEntity.SessionStatus.IN_PROGRESS);

            if (!hasNextQuestion) {
                persistenceService.updateEvaluateStatus(request.sessionId(), AsyncTaskStatus.PENDING, null);
                evaluateStreamProducer.sendEvaluateTask(request.sessionId());
                log.info("面试会话已完成，已提交评估任务: sessionId={}", request.sessionId());
            }
        } catch (Exception e) {
            log.warn("保存面试回答到数据库失败: {}", e.getMessage());
        }

        log.info("提交面试回答: sessionId={}, questionIndex={}, remaining={}",
            request.sessionId(), index, questions.size() - newIndex);

        return new SubmitAnswerResponse(
            hasNextQuestion,
            nextQuestion,
            newIndex,
            questions.size()
        );
    }

    /**
     * 提前完成面试并触发异步评估
     */
    public void completeInterview(String sessionId) {
        CachedSession session = getOrRestoreSession(sessionId);

        if (session.getStatus() == SessionStatus.COMPLETED || session.getStatus() == SessionStatus.EVALUATED) {
            throw new BusinessException(ErrorCode.INTERVIEW_ALREADY_COMPLETED);
        }

        // 更新 Redis 中的会话状态
        sessionCache.updateSessionStatus(sessionId, SessionStatus.COMPLETED);

        try {
            persistenceService.updateSessionStatus(sessionId,
                InterviewSessionEntity.SessionStatus.COMPLETED);
            // 标记评估为待处理
            persistenceService.updateEvaluateStatus(sessionId, AsyncTaskStatus.PENDING, null);
        } catch (Exception e) {
            log.warn("更新面试会话完成状态失败: {}", e.getMessage());
        }

        // 投递异步评估任务
        evaluateStreamProducer.sendEvaluateTask(sessionId);

        log.info("面试会话提前完成，已提交评估任务: sessionId={}", sessionId);
    }

    /**
     * 获取缓存会话，缓存未命中时从数据库恢复
     */
    private CachedSession getOrRestoreSession(String sessionId) {
        // 1. 优先读取 Redis 缓存
        Optional<CachedSession> cachedOpt = sessionCache.getSession(sessionId);
        if (cachedOpt.isPresent()) {
            // 命中缓存时刷新 TTL
            ensureCurrentUserOwns(cachedOpt.get());
            sessionCache.refreshSessionTTL(sessionId);
            return cachedOpt.get();
        }

        // 2. 缓存未命中时从数据库恢复
        CachedSession restoredSession = restoreSessionFromDatabase(sessionId);
        if (restoredSession == null) {
            throw new BusinessException(ErrorCode.INTERVIEW_SESSION_NOT_FOUND);
        }

        return restoredSession;
    }

    /**
     * 将缓存会话转换为前端响应 DTO
     */
    private InterviewSessionDTO toDTO(CachedSession session) {
        List<InterviewQuestionDTO> questions = session.getQuestions(objectMapper);
        return new InterviewSessionDTO(
            session.getSessionId(),
            session.getResumeText(),
            questions.size(),
            session.getCurrentIndex(),
            questions,
            session.getStatus()
        );
    }

    private void ensureCurrentUserOwns(CachedSession session) {
        String ownerUserId = session.getUserId();
        String currentUserId = CurrentUserContext.getRequiredUserId();
        if (ownerUserId == null) {
            boolean exists = persistenceService.findBySessionIdForCurrentUser(session.getSessionId()).isPresent();
            if (!exists) {
                throw new BusinessException(ErrorCode.INTERVIEW_SESSION_NOT_FOUND);
            }
            return;
        }
        if (!ownerUserId.equals(currentUserId)) {
            throw new BusinessException(ErrorCode.INTERVIEW_SESSION_NOT_FOUND);
        }
    }
}
