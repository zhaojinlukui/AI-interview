package interview.guide.modules.voiceinterview.service;

import com.alibaba.dashscope.audio.omni.OmniRealtimeCallback;
import com.alibaba.dashscope.audio.omni.OmniRealtimeConfig;
import com.alibaba.dashscope.audio.omni.OmniRealtimeConversation;
import com.alibaba.dashscope.audio.omni.OmniRealtimeModality;
import com.alibaba.dashscope.audio.omni.OmniRealtimeParam;
import com.alibaba.dashscope.audio.omni.OmniRealtimeTranscriptionParam;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import interview.guide.common.config.AiProperties;
import interview.guide.common.config.ConfigPlaceholderResolver;
import interview.guide.modules.aisettings.service.AiSettingsResolver;
import interview.guide.modules.aisettings.service.AiSettingsResolver.AsrConfigSnapshot;
import interview.guide.modules.voiceinterview.config.VoiceInterviewProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.Base64;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Qwen3实时语音识别服务
 * <p>
 * 基于阿里云DashScope的qwen3-asr-flash-realtime模型提供实时语音识别能力，
 * 负责管理多个并发会话的WebSocket连接，并配合服务端VAD完成音频转写。
 * </p>
 * <p>
 * 核心特性：
 * - 通过线程安全的并发Map管理多会话，支持断线重连
 * - 使用服务端VAD自动检测句子边界，400ms静音时长触发转写
 * - 基于回调分发实时转写结果（中间结果和定稿结果）
 * - 异步建立连接，不阻塞调用线程
 * - 支持用户级配置覆盖，未登录时使用系统默认配置
 * - 会话结束时自动清理资源，避免内存泄漏
 * </p>
 */
@Slf4j
@Service
public class QwenAsrService {

    private final AiProperties aiProperties;
    private final AiSettingsResolver settingsResolver;

    private String url;
    private String model;
    private String apiKey;
    private String language;
    private String format;
    private Integer sampleRate;
    private Boolean enableTurnDetection;
    private String turnDetectionType;
    private Float turnDetectionThreshold;
    private Integer turnDetectionSilenceDurationMs;

    /**
     * 构造函数，从语音面试配置中加载默认语音识别参数
     */
    public QwenAsrService(
            VoiceInterviewProperties voiceInterviewProperties,
            AiProperties aiProperties,
            AiSettingsResolver settingsResolver) {
        this.aiProperties = aiProperties;
        this.settingsResolver = settingsResolver;
        applyAsrConfig(voiceInterviewProperties.getQwen().getAsr());
    }

    /**
     * 重载语音识别配置
     * 用于配置热更新场景
     */
    public void reload(VoiceInterviewProperties voiceInterviewProperties) {
        applyAsrConfig(voiceInterviewProperties.getQwen().getAsr());
        log.info("语音识别服务已重载: model={}, url={}", model, url);
    }

    /**
     * 应用语音识别配置参数
     */
    private void applyAsrConfig(VoiceInterviewProperties.AsrConfig asr) {
        this.url = asr.getUrl();
        this.model = asr.getModel();
        this.apiKey = resolveApiKey(asr.getApiKey());
        this.language = asr.getLanguage();
        this.format = asr.getFormat();
        this.sampleRate = asr.getSampleRate();
        this.enableTurnDetection = asr.isEnableTurnDetection();
        this.turnDetectionType = asr.getTurnDetectionType();
        this.turnDetectionThreshold = asr.getTurnDetectionThreshold();
        this.turnDetectionSilenceDurationMs = asr.getTurnDetectionSilenceDurationMs();
    }

    /**
     * 活跃语音识别会话映射
     * Key：会话ID（业务侧传入的标识）
     * Value：包含OmniRealtimeConversation实例的AsrSession
     */
    private final Map<String, AsrSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, String> sessionUserIds = new ConcurrentHashMap<>(); // 会话ID到用户ID的映射

    /** 防止同一会话ID上并发执行停止/启动操作，并在重连时配合使用 */
    private final ConcurrentHashMap<String, Object> sessionLocks = new ConcurrentHashMap<>();

    /**
     * 获取指定会话的同步锁对象
     */
    private Object lockForSession(String sessionId) {
        return sessionLocks.computeIfAbsent(sessionId, k -> new Object());
    }

    /**
     * 服务初始化
     * 检查API密钥配置状态
     */
    @PostConstruct
    public void init() {
        if (!isConfigured()) {
            log.warn("语音识别服务初始化时未配置API密钥，在配置完成前将保持禁用状态");
            return;
        }
        log.info("语音识别服务已初始化: model={}, url={}", model, url);
    }

    /**
     * 检查服务是否已配置API密钥
     */
    public boolean isConfigured() {
        return apiKey != null && !apiKey.trim().isEmpty();
    }

    /**
     * 启动新的转写会话（仅定稿结果回调）
     */
    public void startTranscription(String sessionId, Consumer<String> onFinal, Consumer<Throwable> onError) {
        startTranscription(sessionId, onFinal, null, onError);
    }

    /**
     * 启动新的转写会话（含中间结果回调）
     */
    public void startTranscription(
            String sessionId,
            Consumer<String> onFinal,
            Consumer<String> onPartial,
            Consumer<Throwable> onError) {
        startTranscription(sessionId, onFinal, onPartial, null, onError);
    }

    public void startTranscription(
            String sessionId,
            Consumer<String> onFinal,
            Consumer<String> onPartial,
            Runnable onReady,
            Consumer<Throwable> onError) {
        startTranscription(sessionId, onFinal, onPartial, onReady, onError, null);
    }

    /**
     * 启动转写会话（完整参数）
     * <p>
     * 创建到DashScope语音识别服务的WebSocket连接，
     * 设置转写结果与错误处理回调，使用服务端VAD自动检测句子边界。
     * 连接在异步线程中建立，不阻塞调用线程。
     * </p>
     *
     * @param sessionId 会话唯一标识
     * @param onFinal 定稿文本回调（completed事件）
     * @param onPartial 中间结果文本回调（实时字幕），可为null
     * @param onReady 连接就绪回调
     * @param onError 错误回调
     * @param userId 用户ID，为null时使用系统默认配置
     */
    public void startTranscription(
            String sessionId,
            Consumer<String> onFinal,
            Consumer<String> onPartial,
            Runnable onReady,
            Consumer<Throwable> onError,
            String userId) {
        synchronized (lockForSession(sessionId)) {
            if (userId != null && !userId.isBlank()) {
                sessionUserIds.put(sessionId, userId);
            }
            startTranscriptionLocked(sessionId, onFinal, onPartial, onReady, onError, resolveConfig(userId));
        }
    }

    /**
     * 重启转写会话（停止后重新启动）
     * 用于断线重连场景，先停止旧会话，等待200ms后重新建立连接
     */
    public void restartTranscription(
            String sessionId,
            Consumer<String> onFinal,
            Consumer<String> onPartial,
            Runnable onReady,
            Consumer<Throwable> onError) {
        restartTranscription(sessionId, onFinal, onPartial, onReady, onError, null);
    }

    public void restartTranscription(
            String sessionId,
            Consumer<String> onFinal,
            Consumer<String> onPartial,
            Runnable onReady,
            Consumer<Throwable> onError,
            String userId) {
        synchronized (lockForSession(sessionId)) {
            log.info("[会话: {}] 正在重启语音识别服务（停止 + 启动）", sessionId);
            if (userId == null || userId.isBlank()) {
                userId = sessionUserIds.get(sessionId);
            }
            // 先停止旧会话
            stopTranscription(sessionId);
            try {
                Thread.sleep(200); // 等待旧连接完全关闭
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            startTranscriptionLocked(sessionId, onFinal, onPartial, onReady, onError, resolveConfig(userId));

            // 校验重连是否成功，最多等待1秒
            for (int attempt = 0; attempt < 10; attempt++) {
                try {
                    Thread.sleep(100);
                    AsrSession newSession = sessions.get(sessionId);
                    if (newSession != null && newSession.isReady()) {
                        log.info("[会话: {}] 语音识别重连验证成功", sessionId);
                        return;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.warn("[会话: {}] 语音识别重连验证被中断", sessionId);
                    return;
                }
            }

            log.warn("[会话: {}] 语音识别重连在1秒后可能尚未完全就绪", sessionId);
        }
    }

    /**
     * 在锁保护下启动转写会话
     * 创建WebSocket连接、配置转写参数并注册事件回调
     */
    private void startTranscriptionLocked(
            String sessionId,
            Consumer<String> onFinal,
            Consumer<String> onPartial,
            Runnable onReady,
            Consumer<Throwable> onError,
            AsrConfigSnapshot configSnapshot) {
        if (sessions.containsKey(sessionId)) {
            throw new IllegalStateException("会话已存在: " + sessionId);
        }
        if (!isConfigured(configSnapshot)) {
            IllegalStateException ex = new IllegalStateException("语音识别API密钥未配置");
            onError.accept(ex);
            throw ex;
        }

        try {
            // 构造WebSocket连接参数
            OmniRealtimeParam param = OmniRealtimeParam.builder()
                    .model(configSnapshot.model())
                    .url(configSnapshot.url())
                    .apikey(configSnapshot.apiKey())
                    .build();

            final AtomicReference<OmniRealtimeConversation> conversationRef = new AtomicReference<>();

            // 创建WebSocket事件回调处理器
            OmniRealtimeCallback callback = new OmniRealtimeCallback() {
                @Override
                public void onOpen() {
                    log.debug("[会话: {}] WebSocket连接已建立", sessionId);
                }

                @Override
                public void onEvent(JsonObject message) {
                    handleServerEvent(sessionId, message, onFinal, onPartial, onError);
                }

                @Override
                public void onClose(int code, String reason) {
                    OmniRealtimeConversation closed = conversationRef.get();
                    log.warn("[会话: {}] 语音识别WebSocket已关闭 - code: {}, reason: {}",
                            sessionId, code, reason);
                    // 仅移除与本次连接对应的会话，避免重连后旧onClose误删新连接
                    sessions.compute(sessionId, (id, existing) -> {
                        if (existing != null && closed != null && existing.getConversation() == closed) {
                            return null;
                        }
                        return existing;
                    });
                }
            };

            // 创建OmniRealtimeConversation实例
            OmniRealtimeConversation conversation = new OmniRealtimeConversation(param, callback);
            conversationRef.set(conversation);
            AsrSession asrSession = new AsrSession(conversation, onFinal, onPartial, onError);

            // 连接前先写入会话Map，确保hasActiveSession()能返回true
            sessions.put(sessionId, asrSession);

            // 异步连接服务端，避免阻塞调用线程
            Thread connectionThread = new Thread(() -> {
                try {
                    conversation.connect();

                    // 配置转写参数
                    OmniRealtimeTranscriptionParam transcriptionParam = new OmniRealtimeTranscriptionParam();
                    transcriptionParam.setLanguage(configSnapshot.language());
                    transcriptionParam.setInputSampleRate(configSnapshot.sampleRate());
                    transcriptionParam.setInputAudioFormat(configSnapshot.format());

                    OmniRealtimeConfig config = OmniRealtimeConfig.builder()
                            .modalities(Collections.singletonList(OmniRealtimeModality.TEXT))
                            .enableTurnDetection(configSnapshot.enableTurnDetection())
                            .turnDetectionType(configSnapshot.turnDetectionType())
                            .turnDetectionThreshold(configSnapshot.turnDetectionThreshold())
                            .turnDetectionSilenceDurationMs(configSnapshot.turnDetectionSilenceDurationMs())
                            .transcriptionConfig(transcriptionParam)
                            .build();

                    // 更新会话配置
                    conversation.updateSession(config);
                    // 检查会话是否已被替换（重连场景）
                    if (sessions.get(sessionId) != asrSession) {
                        log.debug("[会话: {}] 忽略过期的语音识别连接就绪回调", sessionId);
                        return;
                    }
                    asrSession.markReady();
                    if (onReady != null) {
                        onReady.run();
                    }

                    log.info("[会话: {}] 转写会话已成功启动", sessionId);

                } catch (Exception e) {
                    log.error("[会话: {}] 建立连接失败", sessionId, e);
                    sessions.compute(sessionId, (id, existing) -> {
                        if (existing != null && existing.getConversation() == conversation) {
                            return null;
                        }
                        return existing;
                    });
                    onError.accept(e);
                }
            }, "ASR-Connection-" + sessionId);
            connectionThread.setDaemon(true);
            connectionThread.start();

        } catch (Exception e) {
            String errorMsg = "创建转写会话失败: " + sessionId;
            log.error(errorMsg, e);
            sessions.remove(sessionId);
            onError.accept(new IllegalStateException(errorMsg, e));
            throw new IllegalStateException(errorMsg, e);
        }
    }

    /**
     * 向语音识别服务发送待转写的音频数据
     * <p>
     * 音频数据应为16kHz采样率的PCM格式，发送前会进行Base64编码。
     * 启用服务端VAD后，服务会自动检测语音片段并在检测到静音时触发转写。
     * 发送前会等待会话就绪（最多1200ms），未就绪时抛出异常。
     * </p>
     *
     * @param sessionId 会话标识
     * @param audioData 原始PCM音频字节
     * @throws IllegalStateException 当会话不存在或未就绪时抛出
     */
    public void sendAudio(String sessionId, byte[] audioData) {
        AsrSession session = sessions.get(sessionId);
        if (session == null) {
            throw new IllegalStateException("无活跃会话: " + sessionId);
        }

        try {
            // 等待会话就绪，最多1200ms
            if (!session.awaitReady(1200)) {
                throw new IllegalStateException("语音识别会话未就绪: " + sessionId);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("语音识别会话就绪等待被中断: " + sessionId, e);
        }

        try {
            // Base64编码后发送
            String audioBase64 = Base64.getEncoder().encodeToString(audioData);
            session.getConversation().appendAudio(audioBase64);

            log.trace("[会话: {}] 已发送 {} 字节音频数据", sessionId, audioData.length);

        } catch (Exception e) {
            log.error("[会话: {}] 发送音频失败（上游可能重连）", sessionId, e);
            // 抛出以便WebSocket层执行restartTranscription重连
            throw new IllegalStateException("语音识别发送失败: " + sessionId, e);
        }
    }

    /**
     * 停止转写并关闭会话
     * <p>
     * 通知语音识别服务完成待处理的转写，等待最终结果后关闭WebSocket连接。
     * 使用会话锁防止并发操作。
     * </p>
     *
     * @param sessionId 会话标识
     */
    public void stopTranscription(String sessionId) {
        synchronized (lockForSession(sessionId)) {
            AsrSession session = sessions.remove(sessionId);
            sessionUserIds.remove(sessionId);
            // 清理会话锁，避免内存泄漏
            sessionLocks.remove(sessionId);
            if (session == null) {
                log.warn("[会话: {}] 尝试停止不存在的会话", sessionId);
                return;
            }

            try {
                session.getConversation().endSession();
                log.info("[会话: {}] 转写会话已停止", sessionId);
            } catch (InterruptedException e) {
                log.error("[会话: {}] 结束会话时线程被中断", sessionId, e);
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                log.warn("[会话: {}] 结束会话时出错（可能已关闭）: {}", sessionId, e.getMessage());
            }

            try {
                session.getConversation().close();
            } catch (Exception e) {
                log.debug("[会话: {}] 连接已关闭: {}", sessionId, e.getMessage());
            }
        }
    }

    /**
     * 检查指定会话是否处于活跃状态
     */
    public boolean hasActiveSession(String sessionId) {
        return sessions.containsKey(sessionId);
    }

    /**
     * 检查指定会话是否已就绪
     */
    public boolean isReady(String sessionId) {
        AsrSession session = sessions.get(sessionId);
        return session != null && session.isReady();
    }

    /**
     * 销毁服务并清理所有活跃会话
     * Spring容器关闭时自动调用，停止所有活跃会话并释放资源
     */
    @PreDestroy
    public void destroy() {
        log.info("正在销毁语音识别服务，活跃会话数: {}", sessions.size());

        // 停止所有活跃会话
        sessions.keySet().forEach(sessionId -> {
            try {
                stopTranscription(sessionId);
            } catch (Exception e) {
                log.error("[会话: {}] 清理时出错", sessionId, e);
            }
        });

        sessions.clear();
        log.info("语音识别服务已成功销毁");
    }

    /**
     * 处理DashScope语音识别服务端事件
     * <p>
     * 主要处理以下事件：
     * - session.created：服务端会话已创建
     * - session.updated：会话配置已更新
     * - conversation.item.input_audio_transcription.completed：最终转写结果
     * - conversation.item.input_audio_transcription.text/.delta：中间结果，用于实时字幕
     * - error：发生错误
     * </p>
     */
    private void handleServerEvent(
            String sessionId,
            JsonObject message,
            Consumer<String> onFinal,
            Consumer<String> onPartial,
            Consumer<Throwable> onError) {
        try {
            String eventType = message.get("type").getAsString();

            log.trace("[会话: {}] 收到事件: {}", sessionId, eventType);

            switch (eventType) {
                case "session.created":
                    log.debug("[会话: {}] 服务端会话已创建", sessionId);
                    break;

                case "session.updated":
                    log.debug("[会话: {}] 会话配置已更新", sessionId);
                    break;

                case "conversation.item.input_audio_transcription.completed":
                    // 最终转写结果
                    JsonObject transcriptObj = message.getAsJsonObject();
                    String transcript = transcriptObj.get("transcript").getAsString();
                    String language = transcriptObj.has("language") ?
                            transcriptObj.get("language").getAsString() : "unknown";
                    String emotion = transcriptObj.has("emotion") ?
                            transcriptObj.get("emotion").getAsString() : "neutral";

                    log.debug("[会话: {}] 转写完成 - 语言: {}, 情感: {}, 文本: {}",
                            sessionId, language, emotion, transcript);

                    onFinal.accept(transcript);
                    break;

                case "conversation.item.input_audio_transcription.text":
                case "conversation.item.input_audio_transcription.delta":
                    // 中间转写结果
                    dispatchPartialTranscript(sessionId, message, onPartial);
                    break;

                case "error":
                    // 错误事件，解析错误详情
                    JsonObject errorObj = message.getAsJsonObject("error");
                    String errorType = errorObj.has("type") ? errorObj.get("type").getAsString() : "unknown";
                    String errorCode = errorObj.has("code") ? errorObj.get("code").getAsString() : "unknown";
                    String errorMessage = errorObj.has("message") ? errorObj.get("message").getAsString() : "未知错误";

                    String fullErrorMessage = String.format("语音识别错误 [%s/%s]: %s", errorType, errorCode, errorMessage);
                    log.error("[会话: {}] {}", sessionId, fullErrorMessage);

                    onError.accept(new IllegalStateException(fullErrorMessage));
                    break;

                case "session.finished":
                    log.debug("[会话: {}] 服务端会话已结束", sessionId);
                    break;

                case "conversation.item.input_audio_transcription.failed":
                    log.error("[会话: {}] 语音识别转写失败（单句）: {}", sessionId, message);
                    break;

                default:
                    if (eventType != null && eventType.contains("transcription")) {
                        log.debug("[会话: {}] 未处理的转写相关事件: {}", sessionId, message);
                    } else {
                        log.trace("[会话: {}] 未处理的事件类型: {}", sessionId, eventType);
                    }
            }

        } catch (Exception e) {
            log.error("[会话: {}] 处理服务端事件时出错", sessionId, e);
            onError.accept(e);
        }
    }

    /**
     * 转发中间转写结果给实时界面
     * 从语音识别JSON事件中提取可展示文本并回调
     */
    private void dispatchPartialTranscript(
            String sessionId, JsonObject message, Consumer<String> onPartial) {
        if (onPartial == null) {
            log.trace("[会话: {}] 收到中间转写结果（无消费者）", sessionId);
            return;
        }
        String text = extractTranscriptPayload(message);
        if (text != null && !text.isBlank()) {
            onPartial.accept(text);
        } else {
            log.trace("[会话: {}] 中间语音识别事件无可提取文本: {}", sessionId, message);
        }
    }

    /**
     * 从语音识别JSON事件中提取可展示文本
     * <p>
     * 对于conversation.item.input_audio_transcription.text事件，
     * 预览文本由text（已确认前缀）+ stash（草稿后缀）组成。
     * 支持多种事件格式的文本提取。
     * </p>
     */
    static String extractTranscriptPayload(JsonObject message) {
        // 直接的transcript字段
        if (message.has("transcript") && !message.get("transcript").isJsonNull()) {
            JsonElement el = message.get("transcript");
            if (el.isJsonPrimitive()) {
                return el.getAsString();
            }
        }
        // 实时中间结果：text + stash
        if (message.has("text") || message.has("stash")) {
            String prefix = "";
            String suffix = "";
            if (message.has("text") && !message.get("text").isJsonNull() && message.get("text").isJsonPrimitive()) {
                prefix = message.get("text").getAsString();
            }
            if (message.has("stash") && !message.get("stash").isJsonNull() && message.get("stash").isJsonPrimitive()) {
                suffix = message.get("stash").getAsString();
            }
            String combined = prefix + suffix;
            if (!combined.isBlank()) {
                return combined;
            }
        }
        // delta字段
        if (message.has("delta")) {
            JsonElement d = message.get("delta");
            if (d.isJsonPrimitive()) {
                return d.getAsString();
            }
            if (d.isJsonObject()) {
                JsonObject o = d.getAsJsonObject();
                if (o.has("text") && !o.get("text").isJsonNull()) {
                    return o.get("text").getAsString();
                }
                if (o.has("transcript") && !o.get("transcript").isJsonNull()) {
                    return o.get("transcript").getAsString();
                }
            }
        }
        // item.transcript嵌套字段
        if (message.has("item") && message.get("item").isJsonObject()) {
            JsonObject item = message.getAsJsonObject("item");
            if (item.has("transcript") && !item.get("transcript").isJsonNull()) {
                return item.get("transcript").getAsString();
            }
        }
        return null;
    }

    /**
     * 语音识别会话内部类
     * 封装OmniRealtimeConversation实例和就绪状态
     */
    private static class AsrSession {
        private final OmniRealtimeConversation conversation; // 语音识别会话实例
        private final Consumer<String> onFinal; // 定稿文本回调
        private final Consumer<String> onPartial; // 中间结果回调
        private final Consumer<Throwable> onError; // 错误回调
        private final CountDownLatch readyLatch = new CountDownLatch(1); // 就绪锁存器

        AsrSession(
                OmniRealtimeConversation conversation,
                Consumer<String> onFinal,
                Consumer<String> onPartial,
                Consumer<Throwable> onError) {
            this.conversation = conversation;
            this.onFinal = onFinal;
            this.onPartial = onPartial;
            this.onError = onError;
        }

        public OmniRealtimeConversation getConversation() {
            return conversation;
        }

        /**
         * 标记会话已就绪
         */
        void markReady() {
            readyLatch.countDown();
        }

        /**
         * 检查会话是否已就绪
         */
        boolean isReady() {
            return readyLatch.getCount() == 0;
        }

        /**
         * 等待会话就绪，超时返回false
         */
        boolean awaitReady(long timeoutMs) throws InterruptedException {
            return readyLatch.await(timeoutMs, TimeUnit.MILLISECONDS);
        }
    }

    // 配置setter，供测试或运行时配置注入使用

    public void setUrl(String url) {
        this.url = url;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    /**
     * 解析API密钥
     * 优先使用覆盖配置，其次使用大模型API密钥作为回退
     */
    private String resolveApiKey(String overrideApiKey) {
        String override = trimToNull(ConfigPlaceholderResolver.resolve(overrideApiKey));
        if (override != null) {
            return override;
        }
        if (aiProperties == null || aiProperties.getModel() == null) {
            return null;
        }
        return trimToNull(ConfigPlaceholderResolver.resolve(aiProperties.getModel().getApiKey()));
    }

    /**
     * 根据用户ID解析语音识别配置
     * 未登录时使用系统默认配置
     */
    private AsrConfigSnapshot resolveConfig(String userId) {
        return userId == null || userId.isBlank()
                ? systemConfigSnapshot()
                : settingsResolver.resolveAsr(userId);
    }

    /**
     * 构建系统默认配置快照
     */
    private AsrConfigSnapshot systemConfigSnapshot() {
        return new AsrConfigSnapshot(
                url,
                model,
                apiKey,
                language,
                format,
                sampleRate,
                Boolean.TRUE.equals(enableTurnDetection),
                turnDetectionType,
                turnDetectionThreshold,
                turnDetectionSilenceDurationMs
        );
    }

    /**
     * 检查配置快照是否已配置API密钥
     */
    private boolean isConfigured(AsrConfigSnapshot config) {
        return config != null && config.apiKey() != null && !config.apiKey().trim().isEmpty();
    }

    /**
     * 去除字符串首尾空格，空字符串或包含未解析占位符时返回null
     */
    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() || trimmed.contains("${") ? null : trimmed;
    }

    public void setLanguage(String language) {
        this.language = language;
    }

    public void setFormat(String format) {
        this.format = format;
    }

    public void setSampleRate(Integer sampleRate) {
        this.sampleRate = sampleRate;
    }

}