package interview.guide.modules.voiceinterview.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import interview.guide.common.exception.BusinessException;
import interview.guide.modules.voiceinterview.dto.WebSocketControlMessage;
import interview.guide.modules.voiceinterview.dto.WebSocketSubtitleMessage;
import interview.guide.modules.voiceinterview.model.VoiceInterviewMessageEntity;
import interview.guide.modules.voiceinterview.model.VoiceInterviewSessionEntity;
import interview.guide.modules.voiceinterview.config.VoiceInterviewProperties;
import interview.guide.modules.voiceinterview.service.QwenAsrService;
import interview.guide.modules.voiceinterview.service.QwenTtsService;
import interview.guide.modules.voiceinterview.service.VoiceInterviewService;
import interview.guide.modules.voiceinterview.service.VoiceLlmService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 语音面试 WebSocket 处理器
 * <p>
 * 处理语音面试的实时双向音频流，完整处理链路：
 * 用户音频 → 语音识别(STT) → 大模型对话(LLM) → 语音合成(TTS) → AI音频
 * </p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class VoiceInterviewWebSocketHandler extends TextWebSocketHandler implements DisposableBean {

    private final ObjectMapper objectMapper; // JSON序列化/反序列化工具
    private final QwenAsrService sttService; // 语音识别服务（语音转文本）
    private final QwenTtsService ttsService; // 语音合成服务（文本转语音）
    private final VoiceLlmService llmService; // 大模型对话服务
    private final VoiceInterviewService interviewService; // 语音面试业务服务
    private final VoiceInterviewProperties voiceInterviewProperties; // 语音面试配置属性
    private final ObjectProvider<MeterRegistry> meterRegistryProvider; // 监控指标注册器

    /**
     * 语音片段合并调度器，用于将多段语音识别定稿结果合并后再触发大模型对话
     */
    private final ScheduledExecutorService utteranceMergeScheduler = createUtteranceMergeScheduler();

    /**
     * 语音处理管线执行器，大模型对话、语音合成、数据库操作等阻塞任务均运行在虚拟线程上
     */
    private final ExecutorService voicePipelineExecutor = Executors.newVirtualThreadPerTaskExecutor();

    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>(); // 会话ID到WebSocket会话的映射
    private final Map<String, SessionState> sessionStates = new ConcurrentHashMap<>(); // 会话ID到会话状态的映射
    private final Map<String, byte[]> openingAudioCache = new ConcurrentHashMap<>(); // 开场语音缓存

    // 活动跟踪，用于暂停超时检测
    private final Map<String, Long> lastActivityTime = new ConcurrentHashMap<>(); // 各会话的最后活动时间
    private static final long WARNING_TIME_MS = (long) (4.5 * 60 * 1000);  // 警告阈值：4分30秒
    private static final long PAUSE_TIMEOUT_MS = 5 * 60 * 1000;            // 暂停超时：5分钟
    private static final int WS_SEND_TIME_LIMIT_MS = 10_000; // WebSocket发送超时时间
    private static final int WS_SEND_BUFFER_LIMIT_BYTES = 512 * 1024; // WebSocket发送缓冲区大小
    /** AI音频播放结束后的冷却期，防止扬声器尾音被麦克风拾取触发语音识别 */
    private static final long AI_SPEAK_COOLDOWN_MS = 800;
    private static final int MAX_ASR_READY_RETRY = 2; // 语音识别就绪检查最大重试次数
    private static final long ASR_READY_CHECK_DELAY_SECONDS = 10; // 语音识别就绪检查延迟时间
    // 算法类面试的默认开场问题
    private static final String DEFAULT_OPENING_QUESTION_ALGORITHM =
            "你好，我是本场面试官。第一个问题：请你口述一道算法题，不写代码，只讲「问题建模、数据结构选型、步骤、复杂度、边界处理」。";
    // 后端类面试的默认开场问题
    private static final String DEFAULT_OPENING_QUESTION_BACKEND =
            "你好，我是本场面试官。第一个问题：请用 1 分钟介绍一个你深度参与的项目，按三点回答：业务目标、你负责的核心模块、核心技术栈。说完我会立刻追问一个关键技术决策。";

    /**
     * 创建语音片段合并调度器
     * 使用2个守护线程处理语音片段合并的延迟调度任务
     */
    private static ScheduledExecutorService createUtteranceMergeScheduler() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(2, r -> {
            Thread t = new Thread(r, "voice-utterance-merge");
            t.setDaemon(true); // 设置为守护线程，不阻止JVM退出
            return t;
        });
        executor.setRemoveOnCancelPolicy(true); // 取消任务后立即从队列移除
        executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false); // 关闭时不执行已延迟的任务
        return executor;
    }

    /**
     * 预热开场语音缓存
     * 在应用启动时预先合成开场问题的语音，避免首次使用时等待
     */
    @PostConstruct
    void warmupOpeningAudioCache() {
        if (!voiceInterviewProperties.isOpeningAudioWarmupEnabled()) {
            log.info("开场语音缓存预热已禁用");
            return;
        }
        if (!ttsService.isConfigured()) {
            log.info("开场语音缓存预热已跳过：语音合成服务API密钥未配置");
            return;
        }
        voicePipelineExecutor.execute(() -> {
            try {
                VoiceInterviewProperties.OpeningConfig opening = voiceInterviewProperties.getOpening();
                if (opening == null) {
                    return;
                }
                // 收集所有开场问题模板
                LinkedHashSet<String> allTemplates = new LinkedHashSet<>();
                if (opening.getSkillQuestions() != null) {
                    allTemplates.addAll(opening.getSkillQuestions().values());
                }
                allTemplates.add(opening.getAlgorithmQuestion());
                allTemplates.add(opening.getBackendQuestion());
                // 逐个预热语音缓存
                for (String template : allTemplates) {
                    preloadOpeningAudio(template);
                }
                log.info("开场语音缓存预热完成：{} 条", openingAudioCache.size());
            } catch (Exception e) {
                log.warn("开场语音缓存预热已跳过：{}", e.getMessage());
            }
        });
    }

    /**
     * 预加载单条开场语音到缓存
     */
    private void preloadOpeningAudio(String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        byte[] wavAudio = synthesizeToWav(text);
        if (wavAudio.length > 0) {
            openingAudioCache.put(text, wavAudio);
        }
    }

    /**
     * WebSocket连接建立后的处理
     * 初始化语音识别、发送欢迎消息、自动触发开场问题
     */
    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String sessionId = extractSessionId(session);

        // 设置消息大小上限，16kHz、16-bit的1秒PCM音频约32KB，base64后约42KB，256KB足够缓冲
        session.setTextMessageSizeLimit(256 * 1024);
        session.setBinaryMessageSizeLimit(256 * 1024);

        // 包装为支持并发的WebSocket会话
        WebSocketSession safeSession = new ConcurrentWebSocketSessionDecorator(
                session, WS_SEND_TIME_LIMIT_MS, WS_SEND_BUFFER_LIMIT_BYTES
        );

        sessions.put(sessionId, safeSession);
        sessionStates.put(sessionId, new SessionState());
        lastActivityTime.put(sessionId, System.currentTimeMillis());
        log.info("WebSocket连接已建立，会话ID：{}", sessionId);

        try {
            // 启动语音识别服务
            startDashScopeStt(sessionId, safeSession);

            // 发送欢迎消息
            sendMessage(safeSession, createWelcomeMessage());
            // 自动开场：面试官先说开场语并提出第一个问题（仅首次连接、无历史消息时触发）
            triggerOpeningQuestionIfNeeded(sessionId, safeSession);
        } catch (Exception e) {
            log.error("建立WebSocket连接时出错，会话ID：{}", sessionId, e);
            sendError(safeSession, "初始化语音识别失败: " + e.getMessage());
        }
    }

    /**
     * 创建欢迎消息
     */
    private String createWelcomeMessage() {
        return toJson(Map.of(
                "type", "control",
                "action", "welcome",
                "message", "连接成功，准备开始语音面试",
                "timestamp", System.currentTimeMillis()
        ));
    }

    /**
     * 将对象序列化为JSON字符串
     */
    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            log.error("JSON序列化失败", e);
            return "{}";
        }
    }

    /**
     * 向WebSocket会话发送消息
     */
    private void sendMessage(WebSocketSession session, String message) {
        try {
            if (session.isOpen()) {
                session.sendMessage(new TextMessage(message));
                log.debug("消息已发送到会话，内容预览：{}", message.substring(0, Math.min(100, message.length())));
            } else {
                log.warn("会话已关闭，无法发送消息");
            }
        } catch (Exception e) {
            log.error("发送消息到会话时出错", e);
        }
    }

    /**
     * 根据需要触发开场问题
     * 仅在首次连接且无历史消息时触发
     */
    private void triggerOpeningQuestionIfNeeded(String sessionId, WebSocketSession session) {
        voicePipelineExecutor.execute(() -> {
            try {
                if (session == null || !session.isOpen()) {
                    return;
                }

                // 检查是否有历史对话，有则不重复开场
                List<String> history = getHistory(sessionId);
                if (history != null && !history.isEmpty()) {
                    return;
                }

                VoiceInterviewSessionEntity sessionEntity = getSessionEntity(sessionId);
                if (sessionEntity == null) {
                    log.warn("发送开场问题时未找到会话实体：{}", sessionId);
                    return;
                }

                String aiReply = buildOpeningQuestion(sessionEntity);
                if (aiReply == null || aiReply.isBlank()) {
                    return;
                }

                if (!session.isOpen()) {
                    return;
                }

                // 先保存消息到数据库再推送到前端，确保用户提交时数据库中已有该条消息
                saveMessage(sessionId, null, aiReply);
                sendTextMessage(session, aiReply, true);

                // 获取开场语音并发送
                byte[] wavAudio = getOpeningWavAudio(aiReply, sessionId);
                if (wavAudio.length > 0 && session.isOpen()) {
                    sendAudio(session, wavAudio, aiReply);
                }

                log.info("开场问题已发送，会话ID：{}", sessionId);
            } catch (Exception e) {
                log.error("发送开场问题失败，会话ID：{}", sessionId, e);
            }
        });
    }

    /**
     * 获取开场问题的WAV音频，优先使用缓存
     */
    private byte[] getOpeningWavAudio(String text, String sessionId) {
        String cacheKey = openingAudioCacheKey(resolveUserId(sessionId), text);
        byte[] cached = openingAudioCache.get(cacheKey);
        if (cached != null && cached.length > 0) {
            return cached;
        }
        byte[] wav = synthesizeToWav(text, sessionId);
        if (wav.length > 0) {
            openingAudioCache.put(cacheKey, wav);
        }
        return wav;
    }

    /**
     * 合成文本为WAV音频（不指定用户ID）
     */
    private byte[] synthesizeToWav(String text) {
        byte[] pcm = ttsService.synthesize(text);
        if (pcm == null || pcm.length == 0) {
            return new byte[0];
        }
        return convertPcmToWav(pcm);
    }

    /**
     * 合成文本为WAV音频（指定用户ID）
     */
    private byte[] synthesizeToWav(String text, String sessionId) {
        byte[] pcm = ttsService.synthesize(text, resolveUserId(sessionId));
        if (pcm == null || pcm.length == 0) {
            return new byte[0];
        }
        return convertPcmToWav(pcm);
    }

    /**
     * 生成开场语音缓存键
     */
    private String openingAudioCacheKey(String userId, String text) {
        String owner = userId == null || userId.isBlank() ? "system" : userId;
        return owner + ":" + text;
    }

    /**
     * 根据会话的面试技能方向构建开场问题
     */
    private String buildOpeningQuestion(VoiceInterviewSessionEntity sessionEntity) {
        String skillId = sessionEntity.getSkillId() != null ? sessionEntity.getSkillId() : "";
        VoiceInterviewProperties.OpeningConfig opening = voiceInterviewProperties.getOpening();
        Map<String, String> skillQuestions = opening != null ? opening.getSkillQuestions() : null;
        // 优先使用技能专属开场问题
        if (skillQuestions != null) {
            String bySkill = skillQuestions.get(skillId);
            if (bySkill != null && !bySkill.isBlank()) {
                return bySkill;
            }
        }
        // 判断是否为算法类技能
        List<String> algorithmSkills = opening != null && opening.getAlgorithmSkills() != null
                ? opening.getAlgorithmSkills()
                : List.of();

        if (algorithmSkills.contains(skillId)) {
            String configured = opening != null ? opening.getAlgorithmQuestion() : null;
            return configured != null && !configured.isBlank()
                    ? configured
                    : DEFAULT_OPENING_QUESTION_ALGORITHM;
        }
        // 默认使用后端面试开场问题
        String configured = opening != null ? opening.getBackendQuestion() : null;
        return configured != null && !configured.isBlank()
                ? configured
                : DEFAULT_OPENING_QUESTION_BACKEND;
    }

    /**
     * 处理文本消息
     * 支持audio（音频流）和control（控制指令）两种消息类型
     */
    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String sessionId = extractSessionId(session);

        try {
            JsonNode msg = objectMapper.readTree(message.getPayload());
            String type = msg.get("type").asText();
            int messageSize = message.getPayload().length();
            int messageSizeKB = messageSize / 1024;
            if ("audio".equals(type)) {
                log.trace("[WebSocket] 收到音频消息：会话ID={}, 大小={}KB", sessionId, messageSizeKB);
            } else {
                log.info("[WebSocket] 收到消息：会话ID={}, 类型={}, 大小={}KB ({} 字节)",
                        sessionId, type, messageSizeKB, messageSize);
            }

            if (messageSizeKB > 200) {
                log.warn("[WebSocket] 检测到大消息：{}KB", messageSizeKB);
            }

            // 更新最后活动时间，用于暂停超时检测
            lastActivityTime.put(sessionId, System.currentTimeMillis());

            switch (type) {
                case "audio":
                    String audioData = msg.has("data") ? msg.get("data").asText() : null;
                    if (audioData != null && !audioData.isEmpty()) {
                        handleUserAudio(sessionId, audioData);
                    } else {
                        log.warn("收到空的音频消息");
                    }
                    break;
                case "control":
                    try {
                        handleControl(sessionId, objectMapper.treeToValue(msg, WebSocketControlMessage.class));
                    } catch (Exception e) {
                        log.error("处理控制消息失败，会话ID：{}", sessionId, e);
                        sendError(session, "控制消息处理失败: " + e.getMessage());
                    }
                    break;
                default:
                    log.warn("未知消息类型：{}，会话ID：{}", type, sessionId);
            }

        } catch (Exception e) {
            log.error("处理消息失败，会话ID：{}", sessionId, e);
            sendError(session, "消息处理失败: " + e.getMessage());
        }
    }

    /**
     * Bean销毁时关闭线程池
     */
    @Override
    public void destroy() {
        voicePipelineExecutor.shutdownNow();
        utteranceMergeScheduler.shutdownNow();
        try {
            if (!voicePipelineExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                log.warn("语音处理管线执行器未在5秒内终止");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * WebSocket连接关闭后的清理工作
     * 停止语音识别、清理会话状态、自动结束面试会话
     */
    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        String sessionId = extractSessionId(session);
        try {
            sessions.remove(sessionId);
            SessionState removedState = sessionStates.remove(sessionId);
            if (removedState != null) {
                // 中断正在执行的大模型对话线程
                Thread t = removedState.getProcessingThread();
                if (t != null) {
                    t.interrupt();
                }
            }
            lastActivityTime.remove(sessionId);

            // 停止语音识别服务
            sttService.stopTranscription(sessionId);
            log.info("WebSocket连接已关闭，会话ID：{}，状态：{}", sessionId, status);

            // WebSocket异常断开时自动结束会话，防止状态永远停留在进行中
            try {
                interviewService.endSessionIfInProgress(sessionId);
            } catch (Exception endEx) {
                log.warn("断开后自动结束会话失败，会话ID：{}，错误：{}", sessionId, endEx.getMessage());
            }
        } catch (Exception e) {
            log.error("清理会话资源时出错，会话ID：{}", sessionId, e);
        }
    }

    /**
     * WebSocket传输异常处理
     */
    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        log.error("WebSocket传输错误，会话ID：{}", extractSessionId(session), exception);
    }

    /**
     * 判断是否需要恢复语音识别连接（无会话或追加音频失败时）
     */
    private static boolean shouldRecoverAsrConnection(IllegalStateException ex) {
        String m = ex.getMessage();
        if (m == null) {
            return false;
        }
        return m.contains("No active session") || m.contains("ASR append failed");
    }

    /**
     * 判断语音识别服务是否未就绪
     */
    private static boolean isAsrNotReady(IllegalStateException ex) {
        String m = ex.getMessage();
        return m != null && m.contains("ASR session not ready");
    }

    /**
     * 启动语音识别服务
     */
    private void startDashScopeStt(String sessionId, WebSocketSession session) {
        sttService.startTranscription(
                sessionId,
                text -> handleSttResult(sessionId, text, true), // 定稿结果回调
                text -> handleSttResult(sessionId, text, false), // 中间结果回调
                () -> sendAsrReady(session), // 就绪回调
                error -> {
                    log.error("语音识别错误，会话ID：{}", sessionId, error);
                    sendError(session, "语音识别失败: " + error.getMessage());
                },
                resolveUserId(sessionId)
        );

        // 启动语音识别就绪检查
        scheduleAsrReadyCheck(sessionId, session, 0);
    }

    /**
     * 调度语音识别就绪检查任务
     */
    private void scheduleAsrReadyCheck(String sessionId, WebSocketSession session, int retryCount) {
        utteranceMergeScheduler.schedule(
                () -> checkAsrReadyOrRetry(sessionId, session, retryCount),
                ASR_READY_CHECK_DELAY_SECONDS,
                TimeUnit.SECONDS
        );
    }

    /**
     * 检查语音识别是否就绪，未就绪则重试
     */
    private void checkAsrReadyOrRetry(String sessionId, WebSocketSession session, int retryCount) {
        if (session == null || !session.isOpen() || sttService.isReady(sessionId)) {
            return;
        }

        if (retryCount < MAX_ASR_READY_RETRY) {
            int nextRetry = retryCount + 1;
            log.warn("[会话: {}] 语音识别在{}秒后仍未就绪，正在重试 ({}/{})",
                    sessionId, ASR_READY_CHECK_DELAY_SECONDS, nextRetry, MAX_ASR_READY_RETRY);
            sendAsrStatus(session, "asr_reconnecting", "语音识别连接较慢，正在自动重连");
            restartDashScopeStt(sessionId);
            scheduleAsrReadyCheck(sessionId, session, nextRetry);
            return;
        }

        log.warn("[会话: {}] 语音识别在{}次重试后仍未就绪", sessionId, retryCount);
        sendError(session, "语音识别连接准备超时，请检查语音服务配置或稍后重试");
    }

    /**
     * 重新启动语音识别服务（断线重连时使用）
     */
    private void restartDashScopeStt(String sessionId) {
        WebSocketSession session = sessions.get(sessionId);
        if (session == null || !session.isOpen()) {
            return;
        }
        sttService.restartTranscription(
                sessionId,
                text -> handleSttResult(sessionId, text, true),
                text -> handleSttResult(sessionId, text, false),
                () -> sendAsrReady(session),
                error -> {
                    log.error("语音识别错误，会话ID：{}", sessionId, error);
                    sendError(session, "语音识别失败: " + error.getMessage());
                }
        );
    }

    /**
     * 处理用户音频消息
     * 将base64编码的音频数据解码后发送给语音识别服务
     */
    private void handleUserAudio(String sessionId, String base64Audio) {
        WebSocketSession session = sessions.get(sessionId);
        if (session == null) {
            log.warn("会话未找到：{}", sessionId);
            return;
        }

        // AI正在说话或处于回声冷却期时，丢弃麦克风输入，防止回声触发大模型
        SessionState state = sessionStates.get(sessionId);
        if (state != null && state.isAiSpeakingOrCooldown()) {
            return;
        }

        try {
            byte[] audioData = Base64.getDecoder().decode(base64Audio);
            log.debug("收到音频数据，会话ID：{}，大小：{} 字节", sessionId, audioData.length);

            try {
                sttService.sendAudio(sessionId, audioData);
            } catch (IllegalStateException ex) {
                if (isAsrNotReady(ex)) {
                    log.debug("[会话: {}] 语音识别未就绪，丢弃音频片段", sessionId);
                    return;
                } else if (shouldRecoverAsrConnection(ex)) {
                    // 语音识别连接异常，尝试重连后重试
                    log.warn("[会话: {}] 语音识别发送失败 ({})，正在重启服务并重试",
                            sessionId, ex.getMessage() != null ? ex.getMessage() : "未知错误");
                    restartDashScopeStt(sessionId);
                    boolean sent = false;
                    for (int i = 0; i < 15; i++) {
                        try {
                            Thread.sleep(80);
                            sttService.sendAudio(sessionId, audioData);
                            sent = true;
                            break;
                        } catch (IllegalStateException retry) {
                            if (isAsrNotReady(retry)) {
                                continue;
                            }
                            if (!shouldRecoverAsrConnection(retry)) {
                                throw retry;
                            }
                        }
                    }
                    if (!sent) {
                        log.error("[会话: {}] 语音识别重启后仍无法发送", sessionId);
                        sendError(session, "语音识别连接中断，请刷新页面后重试");
                    }
                } else {
                    throw ex;
                }
            }

        } catch (Exception e) {
            log.error("处理用户音频失败，会话ID：{}", sessionId, e);
            String errorMessage = getErrorMessage(e);
            sendError(session, errorMessage);
        }
    }

    /**
     * 处理语音识别回调结果
     * 中间结果用于实时字幕显示，定稿结果累积后提交给大模型
     */
    private void handleSttResult(String sessionId, String recognizedText, boolean isFinalSegment) {
        WebSocketSession session = sessions.get(sessionId);
        SessionState state = sessionStates.get(sessionId);

        if (session == null || state == null) {
            log.warn("会话或状态未找到：{}", sessionId);
            return;
        }

        // 中间结果：更新实时字幕
        if (!isFinalSegment) {
            state.markSttActivity();
            sendSubtitle(session, state.getMergeBufferPreviewWithPartial(recognizedText), false);
            return;
        }

        // 用户已提交、大模型正在处理时，丢弃迟到的定稿片段，防止污染下一轮合并缓冲区
        if (state.isProcessing().get()) {
            log.debug("丢弃大模型处理期间迟到的语音识别定稿片段，会话ID：{}，内容：{}", sessionId, recognizedText);
            return;
        }

        log.debug("语音识别定稿片段，会话ID：{}，内容：{}", sessionId, recognizedText);
        incrementCounter("app.voice.interview.asr.final_segments", "status", "received");

        // 合并多次语音识别定稿片段，只更新实时字幕，是否提交给大模型由前端手动提交控制
        state.appendFinalSttSegment(recognizedText);
        sendSubtitle(session, state.getMergeBufferPreview(), false);
    }

    /**
     * 手动提交：获取合并缓冲区中累积的用户文本并触发大模型对话管线
     */
    private void flushMergedUtteranceToLlm(String sessionId) {
        WebSocketSession session = sessions.get(sessionId);
        SessionState state = sessionStates.get(sessionId);
        if (session == null || state == null || !session.isOpen()) {
            return;
        }
        // 使用CAS确保同一时间只有一个处理在进行
        if (!state.isProcessing().compareAndSet(false, true)) {
            utteranceMergeScheduler.schedule(
                    () -> flushMergedUtteranceToLlm(sessionId),
                    400,
                    TimeUnit.MILLISECONDS);
            return;
        }
        long mergeStartAt = state.getMergeStartedAt();
        String userText = state.takeMergeBufferAndClear();
        if (userText == null || userText.trim().isEmpty()) {
            state.isProcessing().set(false);
            return;
        }
        long mergeWaitMs = Math.max(0, System.currentTimeMillis() - mergeStartAt);
        recordTimerMillis("app.voice.interview.asr.merge_wait", mergeWaitMs, "status", "success");
        state.setAccumulatedText(userText);
        log.info("合并用户语音文本完成，会话ID：{}，触发大模型对话（文本长度 {}）", sessionId, userText.length());

        // 提交到虚拟线程执行阻塞的大模型对话和语音合成管线，立即释放调度器线程
        voicePipelineExecutor.execute(() -> {
            state.setProcessingThread(Thread.currentThread());
            try {
                triggerLlmResponse(sessionId, session, state);
            } finally {
                state.isProcessing().set(false);
                state.setProcessingThread(null);
            }
        });
    }

    /**
     * 为完整用户输入触发大模型回复
     * 启用流式输出时，按句子触发并发的语音合成，使语音合成与大模型生成并行执行
     */
    private void triggerLlmResponse(String sessionId, WebSocketSession session, SessionState state) {
        long turnStartNanos = System.nanoTime();
        state.aiSpeaking.set(true);
        try {
            if (!session.isOpen()) {
                log.warn("WebSocket会话已关闭，跳过大模型回复，会话ID：{}", sessionId);
                return;
            }

            String userText = state.getAccumulatedText();
            if (userText == null || userText.trim().isEmpty()) {
                log.warn("用户文本为空，跳过大模型回复");
                return;
            }

            log.info("获取大模型回复中，会话ID：{}，文本：{}", sessionId, userText);

            VoiceInterviewSessionEntity sessionEntity = getSessionEntity(sessionId);
            if (sessionEntity == null) {
                log.error("未找到会话实体，无法生成大模型回复，会话ID：{}", sessionId);
                sendError(session, "会话不存在，请重新开始面试");
                return;
            }

            List<String> conversationHistory = getHistory(sessionId);

            long llmStartNanos = System.nanoTime();
            AtomicLong firstTokenAtNanos = new AtomicLong(0);
            boolean streamEnabled = voiceInterviewProperties.isLlmStreamingEnabled();
            String aiReply;

            if (streamEnabled) {
                // 句子级并发语音合成：大模型流式输出期间每检测到一个完整句子就启动语音合成
                Semaphore ttsSemaphore = new Semaphore(
                        Math.max(1, voiceInterviewProperties.getMaxConcurrentTtsPerSession()));
                boolean chunkedEnabled = voiceInterviewProperties.isChunkedAudioEnabled();
                long ttsTimeoutSec = Math.max(5, voiceInterviewProperties.getTtsTimeoutSeconds());
                // 分块模式下创建有序语音块发射器
                OrderedTtsChunkEmitter chunkEmitter = chunkedEnabled
                        ? new OrderedTtsChunkEmitter(sessionId, session, ttsSemaphore, ttsTimeoutSec)
                        : null;
                List<CompletableFuture<byte[]>> ttsFutures = new ArrayList<>();

                aiReply = llmService.chatStreamSentences(
                        userText,
                        partialText -> {
                            // 流式部分文本回调
                            if (partialText == null || partialText.isBlank() || !session.isOpen()) {
                                return;
                            }
                            if (firstTokenAtNanos.compareAndSet(0L, System.nanoTime())) {
                                recordTimerSinceNanos(
                                        "app.voice.interview.llm.first_token_latency",
                                        llmStartNanos,
                                        "status", "success"
                                );
                            }
                            sendTextMessage(session, partialText, false);
                        },
                        sentence -> {
                            // 完整句子回调：启动语音合成
                            if (sentence == null || sentence.isBlank()) {
                                return;
                            }
                            if (chunkEmitter != null) {
                                chunkEmitter.submit(sentence);
                                return;
                            }
                            ttsSemaphore.acquireUninterruptibly();
                            CompletableFuture<byte[]> future = CompletableFuture.supplyAsync(() -> {
                                try {
                                    return ttsService.synthesize(sentence, resolveUserId(sessionId));
                                } finally {
                                    ttsSemaphore.release();
                                }
                            }, voicePipelineExecutor);
                            ttsFutures.add(future);
                        },
                        sessionEntity,
                        conversationHistory
                );

                recordTimerSinceNanos("app.voice.interview.llm.duration", llmStartNanos, "status", "success");
                incrementCounter("app.voice.interview.llm.calls", "status", "success", "streaming", "true");
                log.info("大模型回复，会话ID：{}，内容：'{}'", sessionId, aiReply);

                if (!session.isOpen()) {
                    log.warn("大模型处理期间WebSocket已关闭，丢弃回复，会话ID：{}", sessionId);
                    return;
                }

                sendSubtitle(session, userText, true);
                sendTextMessage(session, aiReply, true);
                saveMessage(sessionId, userText, aiReply);

                // 按顺序收集所有语音合成结果（带超时，防止单句合成挂死阻塞整条管线）
                if (chunkEmitter != null) {
                    long ttsStartNanos = System.nanoTime();
                    chunkEmitter.finish();
                    int emittedChunks = chunkEmitter.awaitCompletion();
                    recordTimerSinceNanos("app.voice.interview.tts.duration", ttsStartNanos, "status", "success");
                    if (emittedChunks == 0 && session.isOpen()) {
                        log.info("[会话: {}] 流式语音合成未产生任何片段，降级为全文合成", sessionId);
                        try {
                            byte[] fallbackPcm = ttsService.synthesize(aiReply, resolveUserId(sessionId));
                            if (fallbackPcm != null && fallbackPcm.length > 0) {
                                sendAudio(session, convertPcmToWav(fallbackPcm), aiReply);
                            }
                        } catch (Exception e) {
                            log.warn("[会话: {}] 降级语音合成失败：{}", sessionId, e.getMessage());
                        }
                    }
                } else if (!ttsFutures.isEmpty()) {
                    long ttsStartNanos = System.nanoTime();
                    // 合并模式：收集所有PCM后合并为一个完整音频
                    List<byte[]> pcmChunks = new ArrayList<>();
                    int totalSize = 0;
                    int failedCount = 0;
                    boolean audioSentByFallback = false;
                    for (CompletableFuture<byte[]> f : ttsFutures) {
                        try {
                            byte[] pcm = f.get(ttsTimeoutSec, TimeUnit.SECONDS);
                            if (pcm != null && pcm.length > 0) {
                                pcmChunks.add(pcm);
                                totalSize += pcm.length;
                            }
                        } catch (Exception e) {
                            f.cancel(true);
                            failedCount++;
                            log.warn("[会话: {}] 某句语音合成失败：{}", sessionId, e.getMessage());
                        }
                    }
                    recordTimerSinceNanos("app.voice.interview.tts.duration", ttsStartNanos, "status", "success");

                    if (!session.isOpen()) {
                        log.warn("语音合成期间WebSocket已关闭，丢弃音频，会话ID：{}", sessionId);
                        return;
                    }

                    // 有句子级合成失败且无成功结果时，用完整文本做一次兜底合成
                    if (totalSize == 0 && failedCount > 0 && session.isOpen()) {
                        log.info("[会话: {}] 全部{}句语音合成均失败，降级为全文合成", sessionId, failedCount);
                        try {
                            byte[] fallbackPcm = ttsService.synthesize(aiReply, resolveUserId(sessionId));
                            if (fallbackPcm != null && fallbackPcm.length > 0) {
                                byte[] wavAudio = convertPcmToWav(fallbackPcm);
                                log.info("[会话: {}] 降级语音合成成功，WAV大小：{} 字节", sessionId, wavAudio.length);
                                sendAudio(session, wavAudio, aiReply);
                                audioSentByFallback = true;
                            }
                        } catch (Exception e) {
                            log.warn("[会话: {}] 降级语音合成也失败：{}", sessionId, e.getMessage());
                        }
                    }

                    if (!audioSentByFallback) {
                        if (totalSize > 0 && session.isOpen()) {
                            // 合并所有PCM片段为完整音频并发送
                            byte[] mergedPcm = new byte[totalSize];
                            int offset = 0;
                            for (byte[] chunk : pcmChunks) {
                                System.arraycopy(chunk, 0, mergedPcm, offset, chunk.length);
                                offset += chunk.length;
                            }
                            byte[] wavAudio = convertPcmToWav(mergedPcm);
                            log.info("[会话: {}] 发送合并音频 - {}句，WAV大小：{} 字节",
                                    sessionId, pcmChunks.size(), wavAudio.length);
                            sendAudio(session, wavAudio, aiReply);
                        } else {
                            log.error("[会话: {}] 所有语音合成调用均返回空音频", sessionId);
                            incrementCounter("app.voice.interview.tts.empty_audio", "status", "empty");
                        }
                    }
                }
            } else {
                // 非流式模式：完整生成后再合成语音
                aiReply = llmService.chat(userText, sessionEntity, conversationHistory);
                recordTimerSinceNanos("app.voice.interview.llm.duration", llmStartNanos, "status", "success");
                incrementCounter("app.voice.interview.llm.calls", "status", "success", "streaming", "false");
                log.info("大模型回复，会话ID：{}，内容：'{}'", sessionId, aiReply);

                if (!session.isOpen()) {
                    log.warn("大模型处理期间WebSocket已关闭，丢弃回复，会话ID：{}", sessionId);
                    return;
                }

                sendSubtitle(session, userText, true);
                sendTextMessage(session, aiReply, true);
                saveMessage(sessionId, userText, aiReply);

                long ttsStartNanos = System.nanoTime();
                log.info("[会话: {}] 开始语音合成，文本长度：{}", sessionId, aiReply.length());
                byte[] aiAudio = ttsService.synthesize(aiReply, resolveUserId(sessionId));
                recordTimerSinceNanos("app.voice.interview.tts.duration", ttsStartNanos, "status", "success");

                if (!session.isOpen()) {
                    return;
                }

                if (aiAudio == null || aiAudio.length == 0) {
                    log.error("[会话: {}] 语音合成返回空音频", sessionId);
                    incrementCounter("app.voice.interview.tts.empty_audio", "status", "empty");
                } else {
                    byte[] wavAudio = convertPcmToWav(aiAudio);
                    sendAudio(session, wavAudio, aiReply);
                }
            }

            state.setAccumulatedText("");
            recordTimerSinceNanos("app.voice.interview.turn.duration", turnStartNanos, "status", "success");
            incrementCounter("app.voice.interview.turn.completed", "status", "success");

        } catch (Exception e) {
            log.error("触发大模型回复失败，会话ID：{}", sessionId, e);
            recordTimerSinceNanos("app.voice.interview.turn.duration", turnStartNanos, "status", "failure");
            incrementCounter("app.voice.interview.turn.completed", "status", "failure");
            incrementCounter("app.voice.interview.errors", "stage", "turn");
            if (session.isOpen()) {
                sendError(session, getUserFacingTurnError(e));
            }
        } finally {
            // 结束AI说话状态，记录冷却结束时间
            state.aiSpeaking.set(false);
            state.aiSpeakEndAt.set(System.currentTimeMillis() + AI_SPEAK_COOLDOWN_MS);
        }
    }

    /**
     * 获取面向用户的对话轮次错误提示
     */
    private String getUserFacingTurnError(Exception e) {
        if (e instanceof BusinessException) {
            return e.getMessage();
        }
        return "AI响应失败: " + e.getMessage();
    }

    /**
     * 将异常转换为面向用户的错误提示
     */
    private String getErrorMessage(Exception e) {
        Throwable cause = e.getCause();

        // 识别常见阿里云错误
        if (cause != null) {
            String message = cause.getMessage();
            if (message != null) {
                if (message.contains("403") || message.contains("ACCESS_DENIED")) {
                    return "阿里云语音服务认证失败：AccessKey 无效或已过期。请在 .env 文件中配置正确的 ALIYUN_ACCESS_KEY";
                }
                if (message.contains("timeout") || message.contains("channel inactive")) {
                    return "阿里云语音服务连接超时。请检查网络连接或稍后重试";
                }
            }
        }

        // 默认错误提示
        return "语音处理失败：" + e.getMessage();
    }

    /**
     * 处理控制消息（提交、结束面试、开始阶段等）
     */
    private void handleControl(String sessionId, WebSocketControlMessage control) {
        log.info("控制消息，会话ID：{}，动作：{}，阶段：{}",
                sessionId, control.getAction(), control.getPhase());

        switch (control.getAction()) {
            case "submit":
                // 用户提交：如果有文本则直接设置到合并缓冲区，然后触发大模型对话
                if (control.getData() != null) {
                    Object textObj = control.getData().get("text");
                    if (textObj instanceof String text && !text.isBlank()) {
                        SessionState state = sessionStates.get(sessionId);
                        if (state != null) {
                            state.setMergeBufferDirectly(text);
                        }
                    }
                }
                flushMergedUtteranceToLlm(sessionId);
                break;
            case "end_interview":
                interviewService.endSession(sessionId);
                break;
            case "start_phase":
                interviewService.startPhase(sessionId, control.getPhase());
                break;
        }
    }

    /**
     * 发送字幕消息
     */
    private void sendSubtitle(WebSocketSession session, String text, boolean isFinal) {
        WebSocketSubtitleMessage subtitle = WebSocketSubtitleMessage.builder()
                .type("subtitle")
                .text(text)
                .isFinal(isFinal)
                .build();
        sendMessage(session, toJson(subtitle));
    }

    /**
     * 发送音频消息
     */
    private void sendAudio(WebSocketSession session, byte[] audio, String text) {
        if (!session.isOpen()) {
            return;
        }
        String base64Audio = Base64.getEncoder().encodeToString(audio);
        log.info("发送音频到前端 - WAV大小：{} 字节，Base64长度：{}", audio.length, base64Audio.length());
        sendMessage(session, toJson(Map.of(
                "type", "audio",
                "data", base64Audio,
                "text", text
        )));
    }

    /**
     * 发送文本消息（非最终）
     */
    private void sendTextMessage(WebSocketSession session, String text) {
        sendTextMessage(session, text, false);
    }

    /**
     * 发送文本消息
     */
    private void sendTextMessage(WebSocketSession session, String text, boolean isFinal) {
        sendMessage(session, toJson(Map.of(
                "type", "text",
                "content", text,
                "final", isFinal
        )));
    }

    /**
     * 发送错误消息
     */
    private void sendError(WebSocketSession session, String error) {
        sendMessage(session, toJson(Map.of("type", "error", "message", error)));
    }

    /**
     * 发送语音识别就绪通知
     */
    private void sendAsrReady(WebSocketSession session) {
        sendAsrStatus(session, "asr_ready", "语音识别已就绪");
    }

    /**
     * 发送语音识别状态通知
     */
    private void sendAsrStatus(WebSocketSession session, String action, String message) {
        if (session == null || !session.isOpen()) {
            return;
        }
        sendMessage(session, toJson(Map.of(
                "type", "control",
                "action", action,
                "message", message,
                "timestamp", System.currentTimeMillis()
        )));
    }

    /**
     * 发送音频分块
     */
    private void sendAudioChunk(WebSocketSession session, byte[] wavAudio, int index, boolean isLast) {
        if (!session.isOpen()) {
            return;
        }
        String base64Audio = Base64.getEncoder().encodeToString(wavAudio);
        sendMessage(session, toJson(Map.of(
                "type", "audio_chunk",
                "data", base64Audio,
                "index", index,
                "isLast", isLast
        )));
        log.debug("[会话] 已发送音频分块 index={}, isLast={}, 大小={} 字节", index, isLast, wavAudio.length);
    }

    /**
     * 发送音频播放完成通知
     */
    private void sendAudioComplete(WebSocketSession session) {
        if (session == null || !session.isOpen()) {
            return;
        }
        sendMessage(session, toJson(Map.of(
                "type", "control",
                "action", "audio_complete",
                "message", "面试官语音播放完成",
                "timestamp", System.currentTimeMillis()
        )));
    }

    /**
     * 有序语音分块发射器
     * 负责按句子顺序提交语音合成任务，并按顺序将合成结果以音频分块形式发送给前端
     */
    private class OrderedTtsChunkEmitter {

        private final String sessionId; // 会话ID
        private final WebSocketSession session; // WebSocket会话
        private final Semaphore ttsSemaphore; // 控制并发语音合成数量的信号量
        private final long ttsTimeoutSec; // 语音合成超时时间
        private final Map<Integer, CompletableFuture<byte[]>> futures = new ConcurrentHashMap<>(); // 索引到合成结果的映射
        private final AtomicInteger nextIndex = new AtomicInteger(); // 下一个提交的索引
        private final AtomicInteger emittedChunks = new AtomicInteger(); // 已发送的分块数量
        private final Object lock = new Object(); // 同步锁
        private final CompletableFuture<Integer> completion; // 完成信号
        private volatile int totalChunks = -1; // 总分块数，-1表示未确定

        OrderedTtsChunkEmitter(
                String sessionId,
                WebSocketSession session,
                Semaphore ttsSemaphore,
                long ttsTimeoutSec) {
            this.sessionId = sessionId;
            this.session = session;
            this.ttsSemaphore = ttsSemaphore;
            this.ttsTimeoutSec = ttsTimeoutSec;
            this.completion = CompletableFuture.supplyAsync(this::drainChunks, voicePipelineExecutor);
        }

        /**
         * 提交一个句子进行语音合成
         */
        void submit(String sentence) {
            int index = nextIndex.getAndIncrement();
            ttsSemaphore.acquireUninterruptibly();
            CompletableFuture<byte[]> future = CompletableFuture.supplyAsync(() -> {
                try {
                    return ttsService.synthesize(sentence, resolveUserId(sessionId));
                } finally {
                    ttsSemaphore.release();
                }
            }, voicePipelineExecutor);

            futures.put(index, future);
            synchronized (lock) {
                lock.notifyAll();
            }
        }

        /**
         * 标记所有句子已提交完成
         */
        void finish() {
            synchronized (lock) {
                totalChunks = nextIndex.get();
                lock.notifyAll();
            }
        }

        /**
         * 等待所有分块处理完成
         */
        int awaitCompletion() {
            long timeoutSec = Math.max(ttsTimeoutSec + 2, (ttsTimeoutSec + 1) * Math.max(1, nextIndex.get()));
            try {
                return completion.get(timeoutSec, TimeUnit.SECONDS);
            } catch (Exception e) {
                log.warn("[会话: {}] 流式语音合成分块发射器未正常完成：{}", sessionId, e.getMessage());
                completion.cancel(true);
                int emitted = emittedChunks.get();
                if (emitted > 0) {
                    sendAudioComplete(session);
                }
                return emitted;
            }
        }

        /**
         * 按顺序处理所有分块的语音合成结果
         */
        private int drainChunks() {
            int index = 0;
            try {
                while (true) {
                    CompletableFuture<byte[]> future = waitForFuture(index);
                    if (future == null) {
                        int emitted = emittedChunks.get();
                        if (emitted > 0) {
                            sendAudioComplete(session);
                        }
                        return emitted;
                    }

                    try {
                        byte[] pcm = future.get(ttsTimeoutSec, TimeUnit.SECONDS);
                        if (pcm != null && pcm.length > 0 && session.isOpen()) {
                            sendAudioChunk(session, convertPcmToWav(pcm), index, false);
                            emittedChunks.incrementAndGet();
                        }
                    } catch (Exception e) {
                        future.cancel(true);
                        log.warn("[会话: {}] 流式语音合成分块 {} 失败：{}", sessionId, index, e.getMessage());
                    } finally {
                        futures.remove(index);
                        index++;
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("[会话: {}] 流式语音合成分块发射器被中断", sessionId);
                int emitted = emittedChunks.get();
                if (emitted > 0) {
                    sendAudioComplete(session);
                }
                return emitted;
            }
        }

        /**
         * 等待指定索引的语音合成结果
         */
        private CompletableFuture<byte[]> waitForFuture(int index) throws InterruptedException {
            synchronized (lock) {
                while (!futures.containsKey(index)) {
                    if (totalChunks >= 0 && index >= totalChunks) {
                        return null;
                    }
                    lock.wait(100);
                }
                return futures.get(index);
            }
        }
    }

    /**
     * 记录自指定纳秒时间点以来的耗时指标
     */
    private void recordTimerSinceNanos(String metricName, long startNanos, String... tags) {
        MeterRegistry registry = getRegistry();
        if (registry == null) {
            return;
        }
        long elapsed = Math.max(0, System.nanoTime() - startNanos);
        registry.timer(metricName, tags).record(elapsed, TimeUnit.NANOSECONDS);
    }

    /**
     * 记录毫秒级耗时指标
     */
    private void recordTimerMillis(String metricName, long millis, String... tags) {
        MeterRegistry registry = getRegistry();
        if (registry == null) {
            return;
        }
        registry.timer(metricName, tags).record(Math.max(0, millis), TimeUnit.MILLISECONDS);
    }

    /**
     * 增加计数器指标
     */
    private void incrementCounter(String metricName, String... tags) {
        MeterRegistry registry = getRegistry();
        if (registry == null) {
            return;
        }
        registry.counter(metricName, tags).increment();
    }

    /**
     * 获取监控指标注册器
     */
    private MeterRegistry getRegistry() {
        return meterRegistryProvider.getIfAvailable();
    }

    /**
     * 定时任务：每30秒检查暂停警告和超时
     */
    @Scheduled(fixedRate = 30000)
    public void checkPauseTimeout() {
        long now = System.currentTimeMillis();

        lastActivityTime.forEach((sessionId, lastTime) -> {
            long elapsed = now - lastTime;

            // 4分30秒时发送警告
            if (elapsed > WARNING_TIME_MS && elapsed < PAUSE_TIMEOUT_MS) {
                sendPauseWarning(sessionId);
            }
            // 5分钟时暂停会话
            else if (elapsed >= PAUSE_TIMEOUT_MS) {
                log.warn("会话 {} 已不活动 {} 分钟，正在暂停", sessionId, PAUSE_TIMEOUT_MS / 60000);
                handlePauseTimeout(sessionId);
            }
        });
    }

    /**
     * 定时任务：每5分钟清理过期会话
     */
    @Scheduled(fixedRate = 300_000)
    public void cleanupStaleSessions() {
        try {
            int cleaned = interviewService.cleanupStaleSessions();
            if (cleaned > 0) {
                log.info("过期会话清理完成：{} 个会话被清理", cleaned);
            }
        } catch (Exception e) {
            log.error("清理过期会话时出错", e);
        }
    }

    /**
     * 发送暂停警告通知
     */
    private void sendPauseWarning(String sessionId) {
        WebSocketSession session = sessions.get(sessionId);
        if (session != null && session.isOpen()) {
            sendMessage(session, toJson(Map.of(
                    "type", "control",
                    "action", "pause_timeout_warning",
                    "message", "会话将在30秒后暂停，请继续说话或点击继续",
                    "timestamp", System.currentTimeMillis()
            )));
        }
    }

    /**
     * 处理暂停超时：保存状态并断开连接
     */
    private void handlePauseTimeout(String sessionId) {
        WebSocketSession session = sessions.get(sessionId);

        try {
            if (session != null && session.isOpen()) {
                sendMessage(session, toJson(Map.of(
                        "type", "control",
                        "action", "pause_timeout",
                        "message", "会话因超时已暂停,可在历史记录中恢复",
                        "timestamp", System.currentTimeMillis()
                )));
            }

            // 保存会话状态到数据库
            interviewService.pauseSession(sessionId, "timeout");

            // 关闭WebSocket连接
            if (session != null && session.isOpen()) {
                session.close(CloseStatus.GOING_AWAY);
            }

            // 清理语音识别会话，避免资源泄漏
            sttService.stopTranscription(sessionId);
            sessions.remove(sessionId);
            sessionStates.remove(sessionId);
            lastActivityTime.remove(sessionId);

            log.info("会话 {} 因超时已暂停", sessionId);

        } catch (Exception e) {
            log.error("处理暂停超时时出错，会话ID：{}", sessionId, e);
        }
    }

    /**
     * 从WebSocket URI路径中提取会话ID
     * 路径格式：/ws/voice-interview/{sessionId}
     */
    private String extractSessionId(WebSocketSession session) {
        String path = session.getUri().getPath();
        return path.substring(path.lastIndexOf('/') + 1);
    }

    /**
     * 获取会话聊天历史，从数据库加载对话历史
     */
    private List<String> getHistory(String sessionId) {
        try {
            List<VoiceInterviewMessageEntity> messages = interviewService.getConversationHistory(sessionId);
            List<String> history = new ArrayList<>();
            String pendingAiQuestion = null;

            for (VoiceInterviewMessageEntity msg : messages) {
                String aiText = VoiceInterviewMessageEntity.trimToNull(msg.getAiGeneratedText());
                String userText = VoiceInterviewMessageEntity.trimToNull(msg.getUserRecognizedText());

                // 处理上一条待处理的AI问题
                if (pendingAiQuestion != null) {
                    history.add("面试官：" + pendingAiQuestion);
                    pendingAiQuestion = null;
                    if (userText != null) {
                        history.add("候选人：" + userText);
                    }
                    if (aiText != null) {
                        pendingAiQuestion = aiText;
                    }
                    continue;
                }

                // 正常处理消息
                if (aiText != null && userText != null) {
                    history.add("面试官：" + aiText);
                    history.add("候选人：" + userText);
                } else if (aiText != null) {
                    pendingAiQuestion = aiText;
                } else if (userText != null) {
                    history.add("候选人：" + userText);
                }
            }
            // 添加最后一条待处理的AI问题
            if (pendingAiQuestion != null) {
                history.add("面试官：" + pendingAiQuestion);
            }

            log.debug("从历史加载了 {} 条消息，会话ID：{}", history.size(), sessionId);
            return history;
        } catch (Exception e) {
            log.error("加载对话历史失败，会话ID：{}", sessionId, e);
            return new ArrayList<>();
        }
    }

    /**
     * 从数据库获取会话实体
     */
    private VoiceInterviewSessionEntity getSessionEntity(String sessionId) {
        try {
            Long sessionIdLong = Long.parseLong(sessionId);
            return interviewService.getSession(sessionIdLong);
        } catch (NumberFormatException e) {
            log.error("无效的会话ID格式：{}", sessionId);
            return null;
        }
    }

    /**
     * 根据会话ID解析用户ID
     */
    private String resolveUserId(String sessionId) {
        VoiceInterviewSessionEntity sessionEntity = getSessionEntity(sessionId);
        return sessionEntity == null ? null : sessionEntity.getUserId();
    }

    /**
     * 保存消息到数据库
     */
    private void saveMessage(String sessionId, String userText, String aiText) {
        try {
            interviewService.saveMessage(sessionId, userText, aiText);
            log.debug("消息已保存到数据库，会话ID：{}", sessionId);
        } catch (Exception e) {
            log.error("保存消息失败，会话ID：{}", sessionId, e);
        }
    }

    /**
     * 将PCM音频转换为WAV格式
     * 为PCM数据添加44字节WAV头，方便浏览器播放
     *
     * @param pcmData 原始PCM音频数据（24kHz、16-bit、单声道）
     * @return WAV格式音频数据
     */
    private byte[] convertPcmToWav(byte[] pcmData) {
        // 语音合成实时API使用24000Hz采样率
        int sampleRate = 24000;
        int bitsPerSample = 16;
        int numChannels = 1;
        int byteRate = sampleRate * numChannels * bitsPerSample / 8;
        int blockAlign = numChannels * bitsPerSample / 8;
        int dataSize = pcmData.length;
        int fileSize = dataSize + 36;

        byte[] wavData = new byte[dataSize + 44];

        int pos = 0;

        // RIFF头
        wavData[pos++] = 'R'; wavData[pos++] = 'I'; wavData[pos++] = 'F'; wavData[pos++] = 'F';
        writeIntLE(wavData, pos, fileSize); pos += 4;
        wavData[pos++] = 'W'; wavData[pos++] = 'A'; wavData[pos++] = 'V'; wavData[pos++] = 'E';

        // fmt数据块
        wavData[pos++] = 'f'; wavData[pos++] = 'm'; wavData[pos++] = 't'; wavData[pos++] = ' ';
        writeIntLE(wavData, pos, 16); pos += 4; // 数据块大小
        writeShortLE(wavData, pos, (short) 1); pos += 2; // 音频格式（1 = PCM）
        writeShortLE(wavData, pos, (short) numChannels); pos += 2;
        writeIntLE(wavData, pos, sampleRate); pos += 4;
        writeIntLE(wavData, pos, byteRate); pos += 4;
        writeShortLE(wavData, pos, (short) blockAlign); pos += 2;
        writeShortLE(wavData, pos, (short) bitsPerSample); pos += 2;

        // data数据块
        wavData[pos++] = 'd'; wavData[pos++] = 'a'; wavData[pos++] = 't'; wavData[pos++] = 'a';
        writeIntLE(wavData, pos, dataSize); pos += 4;

        // 复制PCM数据
        System.arraycopy(pcmData, 0, wavData, 44, pcmData.length);

        return wavData;
    }

    /**
     * 按小端格式写入32位整数
     */
    private static void writeIntLE(byte[] buf, int pos, int value) {
        buf[pos] = (byte) (value & 0xFF);
        buf[pos + 1] = (byte) ((value >> 8) & 0xFF);
        buf[pos + 2] = (byte) ((value >> 16) & 0xFF);
        buf[pos + 3] = (byte) ((value >> 24) & 0xFF);
    }

    /**
     * 按小端格式写入16位短整数
     */
    private static void writeShortLE(byte[] buf, int pos, short value) {
        buf[pos] = (byte) (value & 0xFF);
        buf[pos + 1] = (byte) ((value >> 8) & 0xFF);
    }

    /**
     * 会话状态内部类
     * 管理单次语音面试会话的实时状态，包括语音识别文本累积、大模型处理状态、回声抑制等
     */
    private static class SessionState {
        private final AtomicReference<String> accumulatedText = new AtomicReference<>(""); // 累积的用户识别文本
        private final AtomicBoolean processing = new AtomicBoolean(false); // 是否正在处理大模型对话
        /** AI正在播放语音合成音频，期间丢弃麦克风回声 */
        private final AtomicBoolean aiSpeaking = new AtomicBoolean(false);
        /** AI音频播放结束后额外等待的时间点（ms），防止回声尾音被录入 */
        private final AtomicLong aiSpeakEndAt = new AtomicLong(0);
        /** 多段语音识别定稿片段合并缓冲区，防抖后再提交给大模型 */
        private final AtomicReference<String> mergeBuffer = new AtomicReference<>("");
        /** 合并缓冲区开始计时的时间点 */
        private final AtomicLong mergeStartedAt = new AtomicLong(0);
        /** 最近一次语音识别活动时间 */
        private final AtomicLong lastSttActivityAt = new AtomicLong(System.currentTimeMillis());
        /** 当前正在执行大模型对话和语音合成管线的虚拟线程，断连时可中断 */
        private volatile Thread processingThread = null;

        /**
         * 追加语音识别定稿片段到合并缓冲区
         */
        void appendFinalSttSegment(String segment) {
            String s = segment == null ? "" : segment.trim();
            if (s.isEmpty()) {
                return;
            }
            mergeBuffer.updateAndGet(prev -> {
                if (prev == null || prev.isEmpty()) {
                    mergeStartedAt.set(System.currentTimeMillis());
                    return s;
                }
                return joinSegments(prev, s);
            });
            markSttActivity();
        }

        /**
         * 智能拼接两个文本片段，避免重复和断句问题
         */
        private static String joinSegments(String previous, String next) {
            String trimmedPrevious = previous.trim();
            String trimmedNext = next.trim();
            // 如果后一段包含前一段，直接使用后一段
            if (trimmedNext.equals(trimmedPrevious) || trimmedNext.startsWith(trimmedPrevious)) {
                return trimmedNext;
            }
            // 如果前一段以后一段结尾，保留前一段
            if (trimmedPrevious.endsWith(trimmedNext)) {
                return trimmedPrevious;
            }
            // 根据标点符号决定连接方式
            if (trimmedPrevious.endsWith("。") || trimmedPrevious.endsWith("！")
                    || trimmedPrevious.endsWith("？") || trimmedPrevious.endsWith(".")
                    || trimmedPrevious.endsWith("!") || trimmedPrevious.endsWith("?")) {
                return trimmedPrevious + " " + trimmedNext;
            }
            return trimmedPrevious + "，" + trimmedNext;
        }

        /**
         * 获取合并缓冲区的预览文本
         */
        String getMergeBufferPreview() {
            String s = mergeBuffer.get();
            return s == null ? "" : s;
        }

        /**
         * 直接设置合并缓冲区内容
         */
        void setMergeBufferDirectly(String text) {
            String s = text == null ? "" : text.trim();
            if (s.isEmpty()) {
                return;
            }
            mergeBuffer.set(s);
            if (mergeStartedAt.get() == 0) {
                mergeStartedAt.set(System.currentTimeMillis());
            }
        }

        /**
         * 获取合并缓冲区与实时中间结果的预览文本
         */
        String getMergeBufferPreviewWithPartial(String partial) {
            String current = partial == null ? "" : partial.trim();
            if (current.isEmpty()) {
                return getMergeBufferPreview();
            }

            String confirmed = getMergeBufferPreview();
            if (confirmed.isBlank()) {
                return current;
            }
            return joinSegments(confirmed, current);
        }

        /**
         * 取出合并缓冲区内容并清空
         */
        String takeMergeBufferAndClear() {
            mergeStartedAt.set(0);
            return mergeBuffer.getAndSet("");
        }

        /**
         * 标记语音识别活动时间
         */
        void markSttActivity() {
            lastSttActivityAt.set(System.currentTimeMillis());
        }

        long getMergeStartedAt() {
            long value = mergeStartedAt.get();
            return value > 0 ? value : System.currentTimeMillis();
        }

        long getLastSttActivityAt() {
            return lastSttActivityAt.get();
        }

        String getAccumulatedText() {
            return accumulatedText.get();
        }

        void setAccumulatedText(String text) {
            accumulatedText.set(text);
        }

        AtomicBoolean isProcessing() {
            return processing;
        }

        void setProcessingThread(Thread t) {
            this.processingThread = t;
        }

        public Thread getProcessingThread() {
            return processingThread;
        }

        /**
         * 判断AI是否正在说话或处于回声冷却期
         * 在此期间丢弃麦克风输入，防止扬声器回声触发语音识别
         */
        boolean isAiSpeakingOrCooldown() {
            if (aiSpeaking.get()) {
                return true;
            }
            // AI播放结束后的冷却期（默认800ms），防止扬声器尾音被录入
            return System.currentTimeMillis() < aiSpeakEndAt.get();
        }
    }
}