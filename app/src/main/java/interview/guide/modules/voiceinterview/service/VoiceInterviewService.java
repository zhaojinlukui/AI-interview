package interview.guide.modules.voiceinterview.service;

import interview.guide.common.auth.CurrentUserContext;
import interview.guide.common.constant.CommonConstants.InterviewDefaults;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.model.AsyncTaskStatus;
import interview.guide.modules.voiceinterview.config.VoiceInterviewProperties;
import interview.guide.modules.voiceinterview.dto.CreateSessionRequest;
import interview.guide.modules.voiceinterview.dto.VoiceInterviewMessageDTO;
import interview.guide.modules.voiceinterview.dto.SessionMetaDTO;
import interview.guide.modules.voiceinterview.dto.SessionResponseDTO;
import interview.guide.modules.voiceinterview.listener.VoiceEvaluateStreamProducer;
import interview.guide.modules.voiceinterview.model.VoiceInterviewMessageEntity;
import interview.guide.modules.voiceinterview.model.VoiceInterviewSessionEntity;
import interview.guide.modules.voiceinterview.model.VoiceInterviewSessionStatus;
import interview.guide.modules.voiceinterview.repository.VoiceInterviewEvaluationRepository;
import interview.guide.modules.voiceinterview.repository.VoiceInterviewMessageRepository;
import interview.guide.modules.voiceinterview.repository.VoiceInterviewSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 语音面试服务。
 *
 * <p>负责语音面试会话的业务编排，包括会话生命周期、阶段流转、消息持久化、
 * 对话历史查询以及活跃会话的 Redis 缓存。</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class VoiceInterviewService {

    private final VoiceInterviewSessionRepository sessionRepository;
    private final VoiceInterviewMessageRepository messageRepository;
    private final VoiceInterviewEvaluationRepository evaluationRepository;
    private final RedissonClient redissonClient;
    private final VoiceEvaluateStreamProducer voiceEvaluateStreamProducer;
    private final VoiceInterviewProperties voiceInterviewProperties;

    private static final String SESSION_CACHE_KEY_PREFIX = "voice:interview:session:";
    private static final int CACHE_TTL_HOURS = 1;
    /**
     * 创建新的语音面试会话。
     *
     * @param request 会话创建请求，包含面试方向和阶段配置
     * @return 会话详情和 WebSocket 地址
     */
    @Transactional
    public SessionResponseDTO createSession(CreateSessionRequest request) {
        String effectiveSkillId = request.getSkillId() != null ? request.getSkillId() : InterviewDefaults.SKILL_ID;
        String userId = CurrentUserContext.getRequiredUserId();
        VoiceInterviewSessionEntity session = VoiceInterviewSessionEntity.builder()
                .userId(userId)
                .roleType(effectiveSkillId)
                .skillId(effectiveSkillId)
                .difficulty(request.getDifficulty() != null ? request.getDifficulty() : InterviewDefaults.DIFFICULTY)
                .customJdText(request.getCustomJdText())
                .resumeId(request.getResumeId())
                .introEnabled(request.getIntroEnabled())
                .techEnabled(request.getTechEnabled())
                .projectEnabled(request.getProjectEnabled())
                .hrEnabled(request.getHrEnabled())
                .plannedDuration(request.getPlannedDuration())
                .currentPhase(determineFirstPhase(request))
                .build();

        VoiceInterviewSessionEntity saved = sessionRepository.save(session);
        cacheSession(saved);

        log.info("创建语音面试会话: sessionId={}, userId={}, skillId={}, phase={}",
                saved.getId(), userId, effectiveSkillId, saved.getCurrentPhase());

        return buildSessionResponse(saved);
    }

    /**
     * WebSocket 断开后自动结束仍处于 IN_PROGRESS 的会话。
     *
     * <p>用户主动结束会走 {@link #endSession(String)}，这里只处理异常断开或页面关闭后
     * 遗留的进行中会话。</p>
     */
    @Transactional
    public void endSessionIfInProgress(String sessionId) {
        Long sessionIdLong = parseSessionId(sessionId);
        VoiceInterviewSessionEntity session = sessionRepository.findById(sessionIdLong).orElse(null);
        if (session == null || session.getStatus() != VoiceInterviewSessionStatus.IN_PROGRESS) {
            return;
        }
        log.info("WebSocket 断开后自动结束语音面试会话: sessionId={}", sessionId);
        endSession(session);
    }

    /**
     * 结束面试会话并触发异步评估
     *
     * @param sessionId 会话 ID 字符串
     */
    @Transactional
    public void endSession(String sessionId) {
        Long sessionIdLong = parseSessionId(sessionId);
        VoiceInterviewSessionEntity session = getSession(sessionIdLong);

        if (session == null) {
            log.warn("语音面试会话不存在: sessionId={}", sessionId);
            return;
        }

        endSession(session);
    }

    // 结束面试会话
    private void endSession(VoiceInterviewSessionEntity session) {
        session.setEndTime(LocalDateTime.now());
        session.setCurrentPhase(VoiceInterviewSessionEntity.InterviewPhase.COMPLETED);
        session.setStatus(VoiceInterviewSessionStatus.COMPLETED);
        session.setActualDuration((int) Duration.between(session.getStartTime(), LocalDateTime.now()).toSeconds());
        session.setEvaluateStatus(AsyncTaskStatus.PENDING);

        sessionRepository.save(session);
        invalidateSessionCache(session.getId());
        sendEvaluateTaskAfterCommit(String.valueOf(session.getId()));

        log.info("结束语音面试会话: sessionId={}, duration={}s, evaluationStatus=PENDING",
                session.getId(), session.getActualDuration());
    }

    /**
     * 按字符串会话 ID 查询会话，优先读取 Redis 缓存。
     *
     * @param sessionId 会话 ID 字符串
     * @return 会话实体，不存在时返回 null
     */
    public VoiceInterviewSessionEntity getSession(String sessionId) {
        return getSession(parseSessionId(sessionId));
    }

    /**
     * 按数值会话 ID 查询会话，优先读取 Redis 缓存。
     *
     * @param sessionId 会话 ID
     * @return 会话实体，不存在时返回 null
     */
    public VoiceInterviewSessionEntity getSession(Long sessionId) {
        if (sessionId == null) {
            return null;
        }

        // 优先读取缓存。
        String cacheKey = getSessionCacheKey(sessionId);
        RBucket<VoiceInterviewSessionEntity> bucket = redissonClient.getBucket(cacheKey);
        VoiceInterviewSessionEntity cached = bucket.get();

        if (cached != null) {
            log.debug("语音面试会话命中缓存: sessionId={}", sessionId);
            return cached;
        }

        // 缓存未命中时回退到数据库。
        return sessionRepository.findById(sessionId).orElse(null);
    }

    public VoiceInterviewSessionEntity getSessionForCurrentUser(Long sessionId) {
        String userId = CurrentUserContext.getRequiredUserId();
        VoiceInterviewSessionEntity session = getSession(sessionId);
        if (session == null || !userId.equals(session.getUserId())) {
            throw new BusinessException(ErrorCode.VOICE_SESSION_NOT_FOUND, "会话不存在: " + sessionId);
        }
        return session;
    }

    @Transactional
    public void endSessionForCurrentUser(Long sessionId) {
        VoiceInterviewSessionEntity session = getSessionForCurrentUser(sessionId);
        if (session.getStatus() == VoiceInterviewSessionStatus.COMPLETED) {
            return;
        }
        endSession(session);
    }

    /**
     * 开始新的面试阶段。
     *
     * @param sessionId 会话 ID 字符串
     * @param phaseStr 阶段名称，如 INTRO、TECH、PROJECT、HR
     */
    @Transactional
    public void startPhase(String sessionId, String phaseStr) {
        Long sessionIdLong = parseSessionId(sessionId);
        VoiceInterviewSessionEntity session = getSession(sessionIdLong);

        if (session == null) {
            log.warn("无法开始面试阶段，会话不存在: sessionId={}", sessionId);
            return;
        }

        try {
            VoiceInterviewSessionEntity.InterviewPhase newPhase =
                    VoiceInterviewSessionEntity.InterviewPhase.valueOf(phaseStr.toUpperCase());

            VoiceInterviewSessionEntity.InterviewPhase oldPhase = session.getCurrentPhase();
            session.setCurrentPhase(newPhase);
            sessionRepository.save(session);
            cacheSession(session); // 更新缓存。

            log.info("语音面试阶段切换: sessionId={}, from={}, to={}",
                    sessionId, oldPhase, newPhase);

        } catch (IllegalArgumentException e) {
            log.error("无效的面试阶段: phase={}", phaseStr, e);
        }
    }

    /**
     * 获取会话当前面试阶段。
     *
     * @param sessionId 会话 ID 字符串
     * @return 当前阶段；会话不存在时返回 null
     */
    public VoiceInterviewSessionEntity.InterviewPhase getCurrentPhase(String sessionId) {
        VoiceInterviewSessionEntity session = getSession(sessionId);
        return session != null ? session.getCurrentPhase() : null;
    }

    /**
     * 保存一轮对话消息。
     *
     * <p>如果上一条 AI 问题还没有绑定用户回答，会先把本次用户文本回填到那条问题；
     * 本次 AI 文本非空时，再保存新的对话消息。</p>
     *
     * @param sessionId 会话 ID 字符串
     * @param userText 用户语音识别文本
     * @param aiText AI 生成回复文本
     */
    @Transactional
    public void saveMessage(String sessionId, String userText, String aiText) {
        Long sessionIdLong = parseSessionId(sessionId);
        VoiceInterviewSessionEntity session = getSession(sessionIdLong);

        if (session == null) {
            log.warn("无法保存语音面试消息，会话不存在: sessionId={}", sessionId);
            return;
        }

        String normalizedUserText = VoiceInterviewMessageEntity.trimToNull(userText);
        String normalizedAiText = VoiceInterviewMessageEntity.trimToNull(aiText);

        boolean answerAttached = normalizedUserText != null
            && fillLatestUnansweredQuestion(sessionIdLong, normalizedUserText);
        if (normalizedAiText == null) {
            return;
        }

        VoiceInterviewMessageEntity message = VoiceInterviewMessageEntity.builder()
                .sessionId(sessionIdLong)
                .messageType("DIALOGUE")
                .phase(session.getCurrentPhase())
                .userRecognizedText(normalizedUserText != null && !answerAttached
                    ? normalizedUserText
                    : null)
                .aiGeneratedText(normalizedAiText)
                .sequenceNum(getNextSequenceNum(sessionIdLong))
                .build();

        messageRepository.save(message);
        log.debug("Saved message for session: {}, phase: {}, sequence: {}",
                sessionId, session.getCurrentPhase(), message.getSequenceNum());
    }

    private boolean fillLatestUnansweredQuestion(Long sessionId, String userText) {
        return messageRepository
            .findFirstBySessionIdAndUserRecognizedTextIsNullAndAiGeneratedTextIsNotNullOrderBySequenceNumDesc(
                sessionId)
            .map(message -> {
                message.setUserRecognizedText(userText);
                messageRepository.save(message);
                log.debug("回填语音面试回答: sessionId={}, sequence={}",
                    sessionId, message.getSequenceNum());
                return true;
            })
            .orElse(false);
    }

    /**
     * 查询会话对话历史。
     *
     * @param sessionId 会话 ID 字符串
     * @return 按序号升序排列的消息列表
     */
    public List<VoiceInterviewMessageEntity> getConversationHistory(String sessionId) {
        Long sessionIdLong = parseSessionId(sessionId);
        return messageRepository.findBySessionIdOrderBySequenceNumAsc(sessionIdLong);
    }

    /**
     * 查询会话对话历史并转换为前端 DTO。
     */
    public List<VoiceInterviewMessageDTO> getConversationHistoryDTO(String sessionId) {
        return getConversationHistory(sessionId).stream()
            .map(msg -> VoiceInterviewMessageDTO.builder()
                .id(msg.getId())
                .sessionId(msg.getSessionId())
                .messageType(msg.getMessageType())
                .phase(msg.getPhase() != null ? msg.getPhase().name() : null)
                .userRecognizedText(msg.getUserRecognizedText())
                .aiGeneratedText(msg.getAiGeneratedText())
                .timestamp(msg.getTimestamp())
                .sequenceNum(msg.getSequenceNum())
                .build())
            .collect(Collectors.toList());
    }

    /**
     * 暂停语音面试会话。
     *
     * @param sessionId 会话 ID
     * @param reason 暂停原因，如 user_initiated 或 timeout
     */
    @Transactional
    public void pauseSession(String sessionId, String reason) {
        Long sessionIdLong = parseSessionId(sessionId);

        VoiceInterviewSessionEntity session = "timeout".equals(reason)
            ? sessionRepository.findById(sessionIdLong)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "会话不存在: " + sessionId))
            : getSessionForCurrentUser(sessionIdLong);

        if (session.getStatus() != VoiceInterviewSessionStatus.IN_PROGRESS) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                "当前会话状态为 " + session.getStatus() + "，无法暂停"
            );
        }

        session.setStatus(VoiceInterviewSessionStatus.PAUSED);
        session.setPausedAt(LocalDateTime.now());

        sessionRepository.save(session);
        invalidateSessionCache(sessionIdLong);

        log.info("暂停语音面试会话: sessionId={}, reason={}", sessionId, reason);
    }

    /**
     * 恢复语音面试会话。
     *
     * @param sessionId 会话 ID
     * @return 会话详情和 WebSocket 地址
     */
    @Transactional
    public SessionResponseDTO resumeSession(String sessionId) {
        Long sessionIdLong = parseSessionId(sessionId);

        VoiceInterviewSessionEntity session = getSessionForCurrentUser(sessionIdLong);

        if (session.getStatus() != VoiceInterviewSessionStatus.PAUSED) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                "当前会话状态为 " + session.getStatus() + "，无法恢复"
            );
        }

        session.setStatus(VoiceInterviewSessionStatus.IN_PROGRESS);
        session.setResumedAt(LocalDateTime.now());

        VoiceInterviewSessionEntity saved = sessionRepository.save(session);
        cacheSession(saved);

        log.info("恢复语音面试会话: sessionId={}, messageCount={}",
            sessionId, messageRepository.countBySessionId(sessionIdLong));

        return buildSessionResponse(saved);
    }

    /**
     * 查询用户的语音面试会话列表。
     *
     * @param status 状态过滤，可选
     * @return 会话元信息列表
     */
    public List<SessionMetaDTO> getAllSessions(String status) {
        String userId = CurrentUserContext.getRequiredUserId();

        List<VoiceInterviewSessionEntity> sessions;
        if (status != null && !status.isEmpty()) {
            VoiceInterviewSessionStatus statusEnum =
                VoiceInterviewSessionStatus.valueOf(status.toUpperCase());
            sessions = sessionRepository.findByUserIdAndStatusOrderByUpdatedAtDesc(userId, statusEnum);
        } else {
            sessions = sessionRepository.findByUserIdOrderByUpdatedAtDesc(userId);
        }

        if (sessions.isEmpty()) {
            return List.of();
        }

        Map<Long, Integer> scoreBySessionId = new HashMap<>();
        evaluationRepository
            .findBySessionIdIn(sessions.stream().map(VoiceInterviewSessionEntity::getId).toList())
            .forEach(evaluation -> {
                if (evaluation.getSessionId() != null && evaluation.getOverallScore() != null) {
                    scoreBySessionId.putIfAbsent(
                        evaluation.getSessionId(),
                        evaluation.getOverallScore()
                    );
                }
            });

        return sessions.stream()
            .map(session -> {
                Integer overallScore = scoreBySessionId.get(session.getId());
                return SessionMetaDTO.builder()
                    .sessionId(session.getId())
                    .roleType(session.getRoleType())
                    .status(session.getStatus().name())
                    .currentPhase(session.getCurrentPhase().name())
                    .createdAt(session.getCreatedAt())
                    .updatedAt(session.getUpdatedAt())
                    .actualDuration(session.getActualDuration())
                    .messageCount(messageRepository.countBySessionId(session.getId()))
                    .evaluateStatus(resolveEvaluateStatus(session, overallScore))
                    .evaluateError(session.getEvaluateError())
                    .overallScore(overallScore)
                    .build();
            })
            .collect(Collectors.toList());
    }

    private String resolveEvaluateStatus(VoiceInterviewSessionEntity session, Integer overallScore) {
        if (overallScore != null) {
            return AsyncTaskStatus.COMPLETED.name();
        }
        return session.getEvaluateStatus() != null ? session.getEvaluateStatus().name() : null;
    }

    /**
     * 根据阶段耗时和提问数量判断是否应进入下一阶段。
     *
     * @param session 当前会话
     * @param phaseStartTime 当前阶段开始时间
     * @param questionCount 当前阶段已提问数量
     * @return 需要切换阶段时返回 true，否则返回 false
     */
    public boolean shouldTransitionToNextPhase(VoiceInterviewSessionEntity session,
                                                LocalDateTime phaseStartTime,
                                                int questionCount) {
        VoiceInterviewSessionEntity.InterviewPhase currentPhase = session.getCurrentPhase();
        if (currentPhase == null || currentPhase == VoiceInterviewSessionEntity.InterviewPhase.COMPLETED) {
            return false;
        }

        Duration phaseDuration = Duration.between(phaseStartTime, LocalDateTime.now());
        VoiceInterviewProperties.DurationConfig config = getPhaseConfig(currentPhase);

        // 规则 1：达到最大时长后强制切换。
        if (phaseDuration.toMinutes() >= config.getMaxDuration()) {
            log.info("阶段达到最大时长，强制切换: phase={}, maxDuration={}min",
                    currentPhase, config.getMaxDuration());
            return true;
        }

        // 规则 2：达到最大提问数量后建议切换。
        if (questionCount >= config.getMaxQuestions()) {
            log.info("阶段达到最大提问数量，建议切换: phase={}, maxQuestions={}",
                    currentPhase, config.getMaxQuestions());
            return true;
        }

        // 规则 3：达到建议时长且已满足最小提问数量后建议切换。
        if (phaseDuration.toMinutes() >= config.getSuggestedDuration()
                && questionCount >= config.getMinQuestions()) {
            log.info("阶段达到建议切换条件: phase={}, suggestedDuration={}min, questionCount={}",
                    currentPhase, config.getSuggestedDuration(), questionCount);
            return true;
        }

        return false;
    }

    /**
     * 获取当前阶段之后的下一个启用阶段。
     *
     * @param session 当前会话
     * @return 下一个面试阶段；没有后续阶段时返回 COMPLETED
     */
    public VoiceInterviewSessionEntity.InterviewPhase getNextPhase(VoiceInterviewSessionEntity session) {
        VoiceInterviewSessionEntity.InterviewPhase current = session.getCurrentPhase();
        if (current == null) {
            return getFirstEnabledPhase(session);
        }

        return switch (current) {
            case INTRO -> session.getTechEnabled() ? VoiceInterviewSessionEntity.InterviewPhase.TECH :
                    session.getProjectEnabled() ? VoiceInterviewSessionEntity.InterviewPhase.PROJECT :
                            session.getHrEnabled() ? VoiceInterviewSessionEntity.InterviewPhase.HR :
                                    VoiceInterviewSessionEntity.InterviewPhase.COMPLETED;
            case TECH -> session.getProjectEnabled() ? VoiceInterviewSessionEntity.InterviewPhase.PROJECT :
                    session.getHrEnabled() ? VoiceInterviewSessionEntity.InterviewPhase.HR :
                            VoiceInterviewSessionEntity.InterviewPhase.COMPLETED;
            case PROJECT -> session.getHrEnabled() ? VoiceInterviewSessionEntity.InterviewPhase.HR :
                    VoiceInterviewSessionEntity.InterviewPhase.COMPLETED;
            case HR, COMPLETED -> VoiceInterviewSessionEntity.InterviewPhase.COMPLETED;
        };
    }

    // ==================== 私有辅助方法 ====================

    /**
     * 根据创建请求中的启用阶段确定首个面试阶段。
     */
    private VoiceInterviewSessionEntity.InterviewPhase determineFirstPhase(CreateSessionRequest request) {
        if (request.getIntroEnabled()) return VoiceInterviewSessionEntity.InterviewPhase.INTRO;
        if (request.getTechEnabled()) return VoiceInterviewSessionEntity.InterviewPhase.TECH;
        if (request.getProjectEnabled()) return VoiceInterviewSessionEntity.InterviewPhase.PROJECT;
        if (request.getHrEnabled()) return VoiceInterviewSessionEntity.InterviewPhase.HR;
        return VoiceInterviewSessionEntity.InterviewPhase.COMPLETED;
    }

    /**
     * 从会话配置中获取首个启用阶段。
     */
    private VoiceInterviewSessionEntity.InterviewPhase getFirstEnabledPhase(VoiceInterviewSessionEntity session) {
        if (session.getIntroEnabled()) return VoiceInterviewSessionEntity.InterviewPhase.INTRO;
        if (session.getTechEnabled()) return VoiceInterviewSessionEntity.InterviewPhase.TECH;
        if (session.getProjectEnabled()) return VoiceInterviewSessionEntity.InterviewPhase.PROJECT;
        if (session.getHrEnabled()) return VoiceInterviewSessionEntity.InterviewPhase.HR;
        return VoiceInterviewSessionEntity.InterviewPhase.COMPLETED;
    }

    private SessionResponseDTO buildSessionResponse(VoiceInterviewSessionEntity session) {
        return SessionResponseDTO.builder()
                .sessionId(session.getId())
                .roleType(session.getRoleType())
                .currentPhase(session.getCurrentPhase().name())
                .status(session.getStatus().name())
                .startTime(session.getStartTime())
                .plannedDuration(session.getPlannedDuration())
                .webSocketUrl(null)
                .build();
    }

    /**
     * 获取阶段时长配置。
     */
    private VoiceInterviewProperties.DurationConfig getPhaseConfig(VoiceInterviewSessionEntity.InterviewPhase phase) {
        return switch (phase) {
            case INTRO -> voiceInterviewProperties.getPhase().getIntro();
            case TECH -> voiceInterviewProperties.getPhase().getTech();
            case PROJECT -> voiceInterviewProperties.getPhase().getProject();
            case HR -> voiceInterviewProperties.getPhase().getHr();
            default -> new VoiceInterviewProperties.DurationConfig(0, 0, 0, 0, 0);
        };
    }

    /**
     * 获取会话内下一条消息序号。
     */
    private int getNextSequenceNum(Long sessionId) {
        return (int) messageRepository.countBySessionId(sessionId) + 1;
    }

    /**
     * 更新语音面试评估状态，供生产者、消费者和控制器复用。
     */
    public void updateEvaluateStatus(Long sessionId, AsyncTaskStatus status, String error) {
        try {
            sessionRepository.findById(sessionId).ifPresent(session -> {
                session.setEvaluateStatus(status);
                session.setEvaluateError(error);
                sessionRepository.save(session);
                log.debug("更新语音面试评估状态: sessionId={}, status={}", sessionId, status);
            });
        } catch (Exception e) {
            log.error("更新语音面试评估状态失败: sessionId={}, status={}, error={}",
                    sessionId, status, e.getMessage(), e);
        }
    }

    /**
     * 触发指定会话的异步评估。
     */
    @Transactional
    public void triggerEvaluation(Long sessionId) {
        getSessionForCurrentUser(sessionId);
        updateEvaluateStatus(sessionId, AsyncTaskStatus.PENDING, null);
        sendEvaluateTaskAfterCommit(sessionId.toString());
    }

    /**
     * 删除语音面试会话及其评估、消息数据。
     */
    @Transactional
    public void deleteSession(Long sessionId) {
        VoiceInterviewSessionEntity session = getSessionForCurrentUser(sessionId);
        evaluationRepository.findBySessionId(sessionId).ifPresent(evaluationRepository::delete);
        messageRepository.deleteBySessionId(sessionId);
        sessionRepository.delete(session);
        log.info("删除语音面试会话: sessionId={}", sessionId);
    }

    /**
     * 将会话写入 Redis 缓存。
     */
    private void cacheSession(VoiceInterviewSessionEntity session) {
        String cacheKey = getSessionCacheKey(session.getId());
        RBucket<VoiceInterviewSessionEntity> bucket = redissonClient.getBucket(cacheKey);
        bucket.set(session, Duration.ofHours(CACHE_TTL_HOURS));
        log.debug("缓存语音面试会话: sessionId={}", session.getId());
    }

    /**
     * 清理 Redis 中的会话缓存。
     */
    private void invalidateSessionCache(Long sessionId) {
        String cacheKey = getSessionCacheKey(sessionId);
        RBucket<VoiceInterviewSessionEntity> bucket = redissonClient.getBucket(cacheKey);
        bucket.delete();
        log.debug("清理语音面试会话缓存: sessionId={}", sessionId);
    }

    private void sendEvaluateTaskAfterCommit(String sessionId) {
        Runnable sendTask = () -> voiceEvaluateStreamProducer.sendEvaluateTask(sessionId);
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            sendTask.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                sendTask.run();
            }
        });
    }

    /**
     * 生成会话缓存键。
     */
    private String getSessionCacheKey(Long sessionId) {
        return SESSION_CACHE_KEY_PREFIX + sessionId;
    }

    /**
     * 将字符串会话 ID 转为 Long。
     */
    private Long parseSessionId(String sessionId) {
        if (sessionId == null) {
            return null;
        }
        try {
            return Long.parseLong(sessionId);
        } catch (NumberFormatException e) {
            log.error("无效的会话 ID 格式: sessionId={}", sessionId, e);
            return null;
        }
    }

    /**
     * 清理长时间停留在 IN_PROGRESS 的会话和卡住的 PROCESSING 评估任务。
     *
     * <p>由定时任务调用，用于兜底处理异常断开的 WebSocket 会话和超时评估。</p>
     */
    @Transactional
    public int cleanupStaleSessions() {
        LocalDateTime staleThreshold = LocalDateTime.now().minusHours(2);

        List<VoiceInterviewSessionEntity> staleSessions = sessionRepository
            .findByStatusAndStartTimeBefore(VoiceInterviewSessionStatus.IN_PROGRESS, staleThreshold);

        int cleaned = 0;
        for (VoiceInterviewSessionEntity session : staleSessions) {
            log.info("清理超时语音面试会话: sessionId={}, startedAt={}",
                session.getId(), session.getStartTime());
            endSession(session);
            cleaned++;
        }

        LocalDateTime evalStaleThreshold = LocalDateTime.now().minusMinutes(30);
        List<VoiceInterviewSessionEntity> stuckEvals = sessionRepository
            .findByEvaluateStatusAndUpdatedAtBefore(AsyncTaskStatus.PROCESSING, evalStaleThreshold);

        for (VoiceInterviewSessionEntity session : stuckEvals) {
            log.info("重置超时语音面试评估任务: sessionId={}", session.getId());
            session.setEvaluateStatus(AsyncTaskStatus.FAILED);
            session.setEvaluateError("评估任务超时，已重置为失败状态");
            sessionRepository.save(session);
            cleaned++;
        }

        return cleaned;
    }
}
