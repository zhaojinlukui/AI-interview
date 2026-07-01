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
 * Qwen3 实时 ASR 服务。
 *
 * 基于阿里云 DashScope 的 qwen3-asr-flash-realtime 模型提供实时语音识别能力。
 * 该服务负责管理多个并发会话的 WebSocket 连接，并配合服务端 VAD 完成音频转写。
 *
 * 核心能力：
 * - 通过线程安全的并发 Map 管理多会话。
 * - 使用服务端 VAD，并以 400ms 静音时长自动检测句子边界。
 * - 基于回调分发实时转写更新。
 * - 会话结束时自动清理资源。
 *
 * 配置：
 * - 模型：qwen3-asr-flash-realtime。
 * - 音频格式：PCM，16kHz 采样率。
 * - 语言：中文（zh）。
 * - VAD：启用 server_vad。
 *
 * @see OmniRealtimeConversation
 * @see OmniRealtimeCallback
 */
@Slf4j
@Service
public class QwenAsrService {

    private final AiProperties aiProperties;
    private final AiSettingsResolver settingsResolver;

    // 运行时配置值从 VoiceInterviewProperties 加载，setter 保留给测试使用。
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

    public QwenAsrService(
            VoiceInterviewProperties voiceInterviewProperties,
            AiProperties aiProperties,
            AiSettingsResolver settingsResolver) {
        this.aiProperties = aiProperties;
        this.settingsResolver = settingsResolver;
        applyAsrConfig(voiceInterviewProperties.getQwen().getAsr());
    }

    public void reload(VoiceInterviewProperties voiceInterviewProperties) {
        applyAsrConfig(voiceInterviewProperties.getQwen().getAsr());
        log.info("QwenAsrService reloaded: model={}, url={}", model, url);
    }

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
     * 活跃 ASR 会话映射。
     * Key：会话 ID（业务侧传入的标识）。
     * Value：包含 OmniRealtimeConversation 实例和回调的 AsrSession。
     */
    private final Map<String, AsrSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, String> sessionUserIds = new ConcurrentHashMap<>();

    /** 防止同一 interview sessionId 上并发 stop/start；并在重连时与 {@link #sessionLocks} 配合 */
    private final ConcurrentHashMap<String, Object> sessionLocks = new ConcurrentHashMap<>();

    private Object lockForSession(String sessionId) {
        return sessionLocks.computeIfAbsent(sessionId, k -> new Object());
    }

    /**
     * 初始化 ASR 服务。
     * Spring 会在服务构造完成并加载 VoiceInterviewProperties 后自动调用该方法。
     *
     * @throws IllegalStateException 当 apiKey 未配置时抛出
     */
    @PostConstruct
    public void init() {
        if (!isConfigured()) {
            log.warn("QwenAsrService initialized without API key; ASR will stay disabled until configured");
            return;
        }
        log.info("QwenAsrService initialized with model: {}, url: {}", model, url);
    }

    public boolean isConfigured() {
        return apiKey != null && !apiKey.trim().isEmpty();
    }

    /**
     * 启动新的转写会话。
     *
     * 该方法会创建到 DashScope ASR 服务的 WebSocket 连接，
     * 并设置转写结果与错误处理回调。
     *
     * 会话使用服务端 VAD 自动检测句子边界。
     * 当语音被识别并转写后，会通过 onFinal 回调返回文本。
     *
     * @param sessionId 会话唯一标识
     * @param onFinal 句子或片段定稿时的回调（{@code completed} 事件）
     * @param onError 发生错误时的回调
     * @throws IllegalStateException 当会话已存在或服务未初始化时抛出
     */
    public void startTranscription(String sessionId, Consumer<String> onFinal, Consumer<Throwable> onError) {
        startTranscription(sessionId, onFinal, null, onError);
    }

    /**
     * 与 {@link #startTranscription(String, Consumer, Consumer)} 类似，
     * 但会转发 partial 转写结果（{@code conversation.item.input_audio_transcription.text}）
     * 供实时字幕使用。
     *
     * @param onPartial 不需要 partial 时可为空
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
     * 停止旧连接并重新建立（用于 ASR WebSocket 被服务端关闭后恢复识别）。
     */
    public void restartTranscription(
            String sessionId,
            Consumer<String> onFinal,
            Consumer<String> onPartial,
            Consumer<Throwable> onError) {
        restartTranscription(sessionId, onFinal, onPartial, null, onError);
    }

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
            log.info("[Session: {}] Restarting DashScope ASR (stop + start)", sessionId);
            if (userId == null || userId.isBlank()) {
                userId = sessionUserIds.get(sessionId);
            }
            stopTranscription(sessionId);
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            startTranscriptionLocked(sessionId, onFinal, onPartial, onReady, onError, resolveConfig(userId));

            // 校验重连是否成功。
            for (int attempt = 0; attempt < 10; attempt++) {
                try {
                    Thread.sleep(100);
                    AsrSession newSession = sessions.get(sessionId);
                    if (newSession != null && newSession.isReady()) {
                        log.info("[Session: {}] ASR reconnection verified successfully", sessionId);
                        return;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.warn("[Session: {}] ASR reconnection verification interrupted", sessionId);
                    return;
                }
            }

            log.warn("[Session: {}] ASR reconnection may not be fully ready after 1 second", sessionId);
        }
    }

    private void startTranscriptionLocked(
            String sessionId,
            Consumer<String> onFinal,
            Consumer<String> onPartial,
            Runnable onReady,
            Consumer<Throwable> onError,
            AsrConfigSnapshot configSnapshot) {
        if (sessions.containsKey(sessionId)) {
            throw new IllegalStateException("Session already exists: " + sessionId);
        }
        if (!isConfigured(configSnapshot)) {
            IllegalStateException ex = new IllegalStateException("ASR API key is not configured");
            onError.accept(ex);
            throw ex;
        }

        try {
            // 构造带连接配置的 OmniRealtimeParam。
            OmniRealtimeParam param = OmniRealtimeParam.builder()
                    .model(configSnapshot.model())
                    .url(configSnapshot.url())
                    .apikey(configSnapshot.apiKey())
                    .build();

            final AtomicReference<OmniRealtimeConversation> conversationRef = new AtomicReference<>();

            // 创建 WebSocket 事件回调处理器。
            OmniRealtimeCallback callback = new OmniRealtimeCallback() {
                @Override
                public void onOpen() {
                    log.debug("[Session: {}] WebSocket connection established", sessionId);
                }

                @Override
                public void onEvent(JsonObject message) {
                    handleServerEvent(sessionId, message, onFinal, onPartial, onError);
                }

                @Override
                public void onClose(int code, String reason) {
                    OmniRealtimeConversation closed = conversationRef.get();
                    log.warn("[Session: {}] DashScope ASR WebSocket closed - code: {}, reason: {}",
                            sessionId, code, reason);
                    // 仅移除与本次连接对应的会话，避免重连后旧 onClose 误删新连接（典型「第三轮起无声」根因）
                    sessions.compute(sessionId, (id, existing) -> {
                        if (existing != null && closed != null && existing.getConversation() == closed) {
                            return null;
                        }
                        return existing;
                    });
                }
            };

            // 创建 OmniRealtimeConversation 实例。
            OmniRealtimeConversation conversation = new OmniRealtimeConversation(param, callback);
            conversationRef.set(conversation);
            AsrSession asrSession = new AsrSession(conversation, onFinal, onPartial, onError);

            // 连接前先写入会话 Map，确保 hasActiveSession() 能返回 true。
            sessions.put(sessionId, asrSession);

            // 异步连接服务端，避免阻塞调用线程。
            Thread connectionThread = new Thread(() -> {
                try {
                    conversation.connect();

                    // 配置转写参数。
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

                    // 更新会话配置。
                    conversation.updateSession(config);
                    if (sessions.get(sessionId) != asrSession) {
                        log.debug("[Session: {}] Ignoring stale ASR connection ready callback", sessionId);
                        return;
                    }
                    asrSession.markReady();
                    if (onReady != null) {
                        onReady.run();
                    }

                    log.info("[Session: {}] Transcription session started successfully", sessionId);

                } catch (Exception e) {
                    log.error("[Session: {}] Failed to establish connection", sessionId, e);
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
            String errorMsg = "Failed to create transcription session: " + sessionId;
            log.error(errorMsg, e);
            sessions.remove(sessionId);
            onError.accept(new IllegalStateException(errorMsg, e));
            throw new IllegalStateException(errorMsg, e);
        }
    }

    /**
     * 向 ASR 服务发送待转写的音频数据。
     *
     * 音频数据应为 16kHz 采样率的 PCM 格式。
     * 发送到 DashScope 服务前会先进行 Base64 编码。
     *
     * 启用服务端 VAD 后，服务会自动检测语音片段，并在检测到静音时触发转写。
     *
     * @param sessionId 会话标识
     * @param audioData 原始 PCM 音频字节
     * @throws IllegalStateException 当会话不存在时抛出
     */
    public void sendAudio(String sessionId, byte[] audioData) {
        AsrSession session = sessions.get(sessionId);
        if (session == null) {
            throw new IllegalStateException("No active session found: " + sessionId);
        }

        try {
            if (!session.awaitReady(1200)) {
                throw new IllegalStateException("ASR session not ready: " + sessionId);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("ASR session ready wait interrupted: " + sessionId, e);
        }

        try {
            // 将音频数据转换为 Base64。
            String audioBase64 = Base64.getEncoder().encodeToString(audioData);

            // 发送到 ASR 服务。
            session.getConversation().appendAudio(audioBase64);

            log.trace("[Session: {}] Sent {} bytes of audio data", sessionId, audioData.length);

        } catch (Exception e) {
            log.error("[Session: {}] appendAudio failed (upstream may reconnect)", sessionId, e);
            // 抛出以便 WebSocket 层执行 restartTranscription；不在此重复 onError 避免用户先看到红条再恢复
            throw new IllegalStateException("ASR append failed: " + sessionId, e);
        }
    }

    /**
     * 停止转写并关闭会话。
     *
     * 该方法会通知 ASR 服务完成待处理的转写，等待最终结果后关闭 WebSocket 连接。
     *
     * @param sessionId 会话标识
     */
    public void stopTranscription(String sessionId) {
        synchronized (lockForSession(sessionId)) {
            AsrSession session = sessions.remove(sessionId);
            sessionUserIds.remove(sessionId);
            // 清理会话锁，避免内存泄漏。
            sessionLocks.remove(sessionId);
            if (session == null) {
                log.warn("[Session: {}] Attempted to stop non-existent session", sessionId);
                return;
            }

            try {
                session.getConversation().endSession();
                log.info("[Session: {}] Transcription session stopped", sessionId);
            } catch (InterruptedException e) {
                log.error("[Session: {}] Thread interrupted while ending session", sessionId, e);
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                log.warn("[Session: {}] Error while ending session (may already be closed): {}", sessionId, e.getMessage());
            }

            try {
                session.getConversation().close();
            } catch (Exception e) {
                log.debug("[Session: {}] Connection already closed: {}", sessionId, e.getMessage());
            }
        }
    }

    /**
     * 检查指定会话是否处于活跃状态。
     *
     * @param sessionId 会话标识
     * @return 会话存在且活跃时返回 true，否则返回 false
     */
    public boolean hasActiveSession(String sessionId) {
        return sessions.containsKey(sessionId);
    }

    public boolean isReady(String sessionId) {
        AsrSession session = sessions.get(sessionId);
        return session != null && session.isReady();
    }

    /**
     * 销毁服务并清理所有活跃会话。
     *
     * Spring 容器关闭时会自动调用该方法，停止所有活跃会话并释放资源。
     */
    @PreDestroy
    public void destroy() {
        log.info("Destroying QwenAsrService with {} active sessions", sessions.size());

        // 停止所有活跃会话。
        sessions.keySet().forEach(sessionId -> {
            try {
                stopTranscription(sessionId);
            } catch (Exception e) {
                log.error("[Session: {}] Error during cleanup", sessionId, e);
            }
        });

        sessions.clear();
        log.info("QwenAsrService destroyed successfully");
    }

    /**
     * 处理 DashScope ASR 服务端事件。
     *
     * 主要处理以下事件：
     * - session.created：服务端会话已创建。
     * - session.updated：会话配置已更新。
     * - conversation.item.input_audio_transcription.completed：最终转写结果。
     * - conversation.item.input_audio_transcription.text / .delta：partial 转写，用于实时字幕。
     * - error：发生错误。
     *
     * @param sessionId 会话标识
     * @param message 服务端返回的 JSON 事件消息
     * @param onFinal 定稿片段文本回调
     * @param onPartial 流式 partial 文本回调（可选）
     * @param onError 错误回调
     */
    private void handleServerEvent(
            String sessionId,
            JsonObject message,
            Consumer<String> onFinal,
            Consumer<String> onPartial,
            Consumer<Throwable> onError) {
        try {
            String eventType = message.get("type").getAsString();

            log.trace("[Session: {}] Received event: {}", sessionId, eventType);

            switch (eventType) {
                case "session.created":
                    log.debug("[Session: {}] Session created on server", sessionId);
                    break;

                case "session.updated":
                    log.debug("[Session: {}] Session configuration updated", sessionId);
                    break;

                case "conversation.item.input_audio_transcription.completed":
                    // 最终转写结果。
                    JsonObject transcriptObj = message.getAsJsonObject();
                    String transcript = transcriptObj.get("transcript").getAsString();
                    String language = transcriptObj.has("language") ?
                            transcriptObj.get("language").getAsString() : "unknown";
                    String emotion = transcriptObj.has("emotion") ?
                            transcriptObj.get("emotion").getAsString() : "neutral";

                    log.debug("[Session: {}] Transcription completed - language: {}, emotion: {}, text: {}",
                            sessionId, language, emotion, transcript);

                    onFinal.accept(transcript);
                    break;

                case "conversation.item.input_audio_transcription.text":
                case "conversation.item.input_audio_transcription.delta":
                    dispatchPartialTranscript(sessionId, message, onPartial);
                    break;

                case "error":
                    // 错误事件。
                    JsonObject errorObj = message.getAsJsonObject("error");
                    String errorType = errorObj.has("type") ? errorObj.get("type").getAsString() : "unknown";
                    String errorCode = errorObj.has("code") ? errorObj.get("code").getAsString() : "unknown";
                    String errorMessage = errorObj.has("message") ? errorObj.get("message").getAsString() : "Unknown error";

                    String fullErrorMessage = String.format("ASR Error [%s/%s]: %s", errorType, errorCode, errorMessage);
                    log.error("[Session: {}] {}", sessionId, fullErrorMessage);

                    onError.accept(new IllegalStateException(fullErrorMessage));
                    break;

                case "session.finished":
                    log.debug("[Session: {}] Session finished on server", sessionId);
                    break;

                case "conversation.item.input_audio_transcription.failed":
                    log.error("[Session: {}] ASR transcription failed (single utterance): {}", sessionId, message);
                    break;

                default:
                    if (eventType != null && eventType.contains("transcription")) {
                        log.debug("[Session: {}] Unhandled transcription-related event: {}", sessionId, message);
                    } else {
                        log.trace("[Session: {}] Unhandled event type: {}", sessionId, eventType);
                    }
            }

        } catch (Exception e) {
            log.error("[Session: {}] Error processing server event", sessionId, e);
            onError.accept(e);
        }
    }

    /**
     * 转发 partial 或流式 ASR 文本给实时界面，单独启用 VAD 不代表前端可见 STT。
     */
    private void dispatchPartialTranscript(
            String sessionId, JsonObject message, Consumer<String> onPartial) {
        if (onPartial == null) {
            log.trace("[Session: {}] Partial transcription received (no consumer)", sessionId);
            return;
        }
        String text = extractTranscriptPayload(message);
        if (text != null && !text.isBlank()) {
            onPartial.accept(text);
        } else {
            log.trace("[Session: {}] Partial ASR event without extractable text: {}", sessionId, message);
        }
    }

    /**
     * 从 ASR JSON 事件中提取可展示文本。
     * <p>
     * 对于 {@code conversation.item.input_audio_transcription.text}，
     * 官方预览文本为 {@code text}（已确认前缀）+ {@code stash}（草稿后缀），两者都可能为空。
     * </p>
     */
    static String extractTranscriptPayload(JsonObject message) {
        if (message.has("transcript") && !message.get("transcript").isJsonNull()) {
            JsonElement el = message.get("transcript");
            if (el.isJsonPrimitive()) {
                return el.getAsString();
            }
        }
        // 实时 partial：text + stash（见阿里云 qwen-asr-realtime 服务端事件文档）。
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
        if (message.has("item") && message.get("item").isJsonObject()) {
            JsonObject item = message.getAsJsonObject("item");
            if (item.has("transcript") && !item.get("transcript").isJsonNull()) {
                return item.get("transcript").getAsString();
            }
        }
        return null;
    }

    /**
     * 保存会话数据的内部类。
     */
    private static class AsrSession {
        private final OmniRealtimeConversation conversation;
        private final Consumer<String> onFinal;
        private final Consumer<String> onPartial;
        private final Consumer<Throwable> onError;
        private final CountDownLatch readyLatch = new CountDownLatch(1);

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

        public Consumer<Throwable> getOnError() {
            return onError;
        }

        void markReady() {
            readyLatch.countDown();
        }

        boolean isReady() {
            return readyLatch.getCount() == 0;
        }

        boolean awaitReady(long timeoutMs) throws InterruptedException {
            return readyLatch.await(timeoutMs, TimeUnit.MILLISECONDS);
        }
    }

    // 配置 setter，供测试或运行时配置注入使用。

    public void setUrl(String url) {
        this.url = url;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

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

    private AsrConfigSnapshot resolveConfig(String userId) {
        return userId == null || userId.isBlank()
                ? systemConfigSnapshot()
                : settingsResolver.resolveAsr(userId);
    }

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

    private boolean isConfigured(AsrConfigSnapshot config) {
        return config != null && config.apiKey() != null && !config.apiKey().trim().isEmpty();
    }

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

    public void setEnableTurnDetection(Boolean enableTurnDetection) {
        this.enableTurnDetection = enableTurnDetection;
    }

    public void setTurnDetectionType(String turnDetectionType) {
        this.turnDetectionType = turnDetectionType;
    }

    public void setTurnDetectionThreshold(Float turnDetectionThreshold) {
        this.turnDetectionThreshold = turnDetectionThreshold;
    }

    public void setTurnDetectionSilenceDurationMs(Integer turnDetectionSilenceDurationMs) {
        this.turnDetectionSilenceDurationMs = turnDetectionSilenceDurationMs;
    }
}
