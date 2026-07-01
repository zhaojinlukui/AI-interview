package interview.guide.modules.voiceinterview.service;

import com.alibaba.dashscope.audio.qwen_tts_realtime.QwenTtsRealtime;
import com.alibaba.dashscope.audio.qwen_tts_realtime.QwenTtsRealtimeAudioFormat;
import com.alibaba.dashscope.audio.qwen_tts_realtime.QwenTtsRealtimeCallback;
import com.alibaba.dashscope.audio.qwen_tts_realtime.QwenTtsRealtimeConfig;
import com.alibaba.dashscope.audio.qwen_tts_realtime.QwenTtsRealtimeParam;
import com.google.gson.JsonObject;
import interview.guide.common.auth.CurrentUserContext;
import interview.guide.common.config.AiProperties;
import interview.guide.common.config.ConfigPlaceholderResolver;
import interview.guide.modules.aisettings.service.AiSettingsResolver;
import interview.guide.modules.aisettings.service.AiSettingsResolver.TtsConfigSnapshot;
import interview.guide.modules.voiceinterview.config.VoiceInterviewProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.Base64;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Qwen 实时 TTS 服务（基于 WebSocket）。
 *
 * 通过阿里云 DashScope 的 qwen-tts-realtime 模型和 WebSocket API
 * 提供实时文本转语音合成能力。
 *
 * 核心能力：
 * - 基于 WebSocket 的实时 TTS 合成。
 * - 使用 commit 模式，由调用方手动控制提交。
 * - 同步合成接口内置 30 秒超时保护。
 * - 通过 response.audio.delta 事件自动收集音频分片。
 * - 支持中文语音，可配置音色、语速和音量。
 *
 * 配置：
 * - 模型：qwen-tts-realtime。
 * - 音色：可配置（Cherry、Serena、Ethan 等）。
 * - 音频格式：PCM，24kHz 采样率。
 * - 模式：commit（调用方控制）。
 *
 * @see QwenTtsRealtime
 * @see QwenTtsRealtimeCallback
 */
@Slf4j
@Service
public class QwenTtsService {

    private final AiProperties aiProperties;
    private final AiSettingsResolver settingsResolver;

    // 运行时配置值从 VoiceInterviewProperties 加载，setter 保留给测试使用。
    private String model;

    private String apiKey;

    private String voice;

    private String format;

    private Integer sampleRate;

    private String mode;

    private String languageType;

    private Float speechRate;

    private Integer volume;

    public QwenTtsService(
            VoiceInterviewProperties voiceInterviewProperties,
            AiProperties aiProperties,
            AiSettingsResolver settingsResolver) {
        this.aiProperties = aiProperties;
        this.settingsResolver = settingsResolver;
        applyTtsConfig(voiceInterviewProperties.getQwen().getTts());
    }

    public void reload(VoiceInterviewProperties voiceInterviewProperties) {
        applyTtsConfig(voiceInterviewProperties.getQwen().getTts());
        log.info("QwenTtsService reloaded: model={}, voice={}", model, voice);
    }

    private void applyTtsConfig(VoiceInterviewProperties.QwenTtsConfig tts) {
        this.model = tts.getModel();
        this.apiKey = resolveApiKey(tts.getApiKey());
        this.voice = tts.getVoice();
        this.format = tts.getFormat();
        this.sampleRate = tts.getSampleRate();
        this.mode = tts.getMode();
        this.languageType = tts.getLanguageType();
        this.speechRate = tts.getSpeechRate();
        this.volume = tts.getVolume();
    }

    public boolean isConfigured() {
        return apiKey != null && !apiKey.trim().isEmpty();
    }

    /**
     * 初始化 TTS 服务。
     * Spring 会在服务构造完成并加载 VoiceInterviewProperties 后自动调用该方法。
     *
     * @throws IllegalStateException 当 apiKey 未配置时抛出
     */
    @PostConstruct
    public void init() {
        if (!isConfigured()) {
            log.warn("QwenTtsService initialized without API key; TTS will stay disabled until configured");
            return;
        }
        log.info("QwenTtsService initialized with model: {}, voice: {}, sampleRate: {}Hz",
                 model, voice, sampleRate);
    }

    /**
     * 将文本合成为语音音频。
     *
     * 该方法使用 DashScope 基于 WebSocket 的 TTS API 同步生成 PCM 音频。
     * 它会建立 WebSocket 连接，发送待合成文本，收集音频分片并返回完整音频数据。
     *
     * 方法通过 CountDownLatch 等待合成完成，并设置 30 秒超时以避免无限阻塞。
     *
     * @param text 待合成文本，null、空字符串或仅空白文本会返回空数组
     * @return 按配置采样率生成的 PCM 音频数据，合成失败时返回空数组
     */
    public byte[] synthesize(String text) {
        return synthesize(text, CurrentUserContext.getCurrentUserIdOrNull());
    }

    public byte[] synthesize(String text, String userId) {
        TtsConfigSnapshot configSnapshot = resolveConfig(userId);
        // 处理 null、空字符串或仅空白文本。
        if (text == null || text.trim().isEmpty()) {
            log.debug("Empty or null text provided, returning empty audio array");
            return new byte[0];
        }
        if (!isConfigured(configSnapshot)) {
            log.warn("TTS synthesis skipped: API key is not configured");
            return new byte[0];
        }

        log.debug("Starting TTS synthesis for text: {} characters", text.length());

        // 同步等待用的锁存器。
        CountDownLatch synthesisLatch = new CountDownLatch(1);

        // 收集音频数据的容器。
        ByteArrayContainer audioContainer = new ByteArrayContainer();

        // 错误容器。
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        // 跟踪响应 ID 的容器。
        AtomicReference<String> responseIdRef = new AtomicReference<>();

        try {
            // 构造带连接配置的 QwenTtsRealtimeParam。
            QwenTtsRealtimeParam param = QwenTtsRealtimeParam.builder()
                    .model(configSnapshot.model())
                    .apikey(configSnapshot.apiKey())
                    .build();

            // 创建 WebSocket 事件回调处理器。
            QwenTtsRealtimeCallback callback = new QwenTtsRealtimeCallback() {
                @Override
                public void onOpen() {
                    log.debug("TTS WebSocket connection established");
                }

                @Override
                public void onEvent(JsonObject message) {
                    handleServerEvent(message, audioContainer, synthesisLatch, errorRef, responseIdRef);
                }

                @Override
                public void onClose(int code, String reason) {
                    log.debug("TTS WebSocket closed - code: {}, reason: {}", code, reason);
                    synthesisLatch.countDown();
                }
            };

            // 创建 QwenTtsRealtime 实例。
            QwenTtsRealtime qwenTtsRealtime = new QwenTtsRealtime(param, callback);

            try {
                // 连接服务端（阻塞调用）。
                qwenTtsRealtime.connect();

                // 配置 TTS 会话参数。
                QwenTtsRealtimeConfig config = QwenTtsRealtimeConfig.builder()
                        .voice(configSnapshot.voice())
                        .responseFormat(getAudioFormat())
                        .mode(configSnapshot.mode())
                        .languageType(configSnapshot.languageType())
                        .speechRate(configSnapshot.speechRate())
                        .volume(configSnapshot.volume())
                        .build();

                // 更新会话配置。
                qwenTtsRealtime.updateSession(config);

                log.info("[TTS] Session configured with voice: {}, triggering synthesis for text (length: {})",
                         configSnapshot.voice(), text.length());

                // 使用 commit 模式发送待合成文本。
                qwenTtsRealtime.appendText(text);
                qwenTtsRealtime.commit();

                log.info("[TTS] Text sent to TTS service, waiting for audio response...");

                // 等待合成完成并设置超时。
                boolean completed = synthesisLatch.await(30, TimeUnit.SECONDS);

                if (!completed) {
                    log.error("TTS synthesis timeout after 30 seconds");
                    return new byte[0];
                }

                // 检查是否发生错误。
                Throwable error = errorRef.get();
                if (error != null) {
                    log.error("TTS synthesis failed", error);
                    return new byte[0];
                }

                // 返回收集到的音频数据。
                byte[] audioData = audioContainer.toByteArray();
                log.info("[TTS] Synthesis completed successfully - {} bytes of audio data, responseId: {}",
                         audioData.length, responseIdRef.get());

                return audioData;

            } finally {
                // 确保连接被关闭。
                try {
                    qwenTtsRealtime.close();
                } catch (Exception e) {
                    log.error("Error closing TTS connection", e);
                }
            }

        } catch (InterruptedException e) {
            log.error("TTS synthesis interrupted", e);
            Thread.currentThread().interrupt();
            return new byte[0];
        } catch (Exception e) {
            log.error("Failed to synthesize text", e);
            return new byte[0];
        }
    }

    /**
     * 获取 Qwen TTS Realtime 的音频格式。
     * 当前支持 24kHz PCM 格式。
     *
     * @return QwenTtsRealtimeAudioFormat 枚举值
     */
    private QwenTtsRealtimeAudioFormat getAudioFormat() {
        // Qwen TTS Realtime 默认使用 24kHz。
        return QwenTtsRealtimeAudioFormat.PCM_24000HZ_MONO_16BIT;
    }

    /**
     * 销毁服务并清理资源。
     *
     * Spring 容器关闭时会自动调用该方法。
     * 当前每次合成都会创建独立临时连接，因此没有持久资源需要清理。
     */
    @PreDestroy
    public void destroy() {
        log.info("QwenTtsService destroyed successfully");
    }

    /**
     * 处理 DashScope TTS 服务端事件。
     *
     * 主要处理以下事件：
     * - session.created：服务端会话已创建。
     * - session.updated：会话配置已更新。
     * - response.audio.delta：收到音频分片。
     * - response.done：响应已完成。
     * - error：发生错误。
     *
     * @param message 服务端返回的 JSON 事件消息
     * @param audioContainer 音频分片收集容器
     * @param synthesisLatch 标记合成完成的锁存器
     * @param errorRef 错误跟踪容器
     * @param responseIdRef 响应 ID 跟踪容器
     */
    private void handleServerEvent(JsonObject message, ByteArrayContainer audioContainer,
                                    CountDownLatch synthesisLatch, AtomicReference<Throwable> errorRef,
                                    AtomicReference<String> responseIdRef) {
        try {
            String eventType = message.get("type").getAsString();

            if (log.isTraceEnabled()) {
                log.trace("Received TTS event: {}, full message: {}", eventType, message);
            } else {
                log.debug("Received TTS event: {}", eventType);
            }

            switch (eventType) {
                case "session.created":
                    String sessionId = message.has("session") && message.get("session").isJsonObject()
                            ? message.get("session").getAsJsonObject().get("id").getAsString()
                            : "unknown";
                    log.debug("TTS session created: {}", sessionId);
                    break;

                case "session.updated":
                    log.debug("TTS session configuration updated");
                    break;

                case "response.audio.delta":
                    // 收到音频分片，delta 本身就是 base64 字符串。
                    if (message.has("delta")) {
                        String audioBase64 = message.get("delta").getAsString();
                        if (audioBase64 != null && !audioBase64.isEmpty()) {
                            byte[] audioChunk = Base64.getDecoder().decode(audioBase64);
                            audioContainer.append(audioChunk);
                            log.trace("Received audio chunk - {} bytes", audioChunk.length);
                        }
                    }
                    break;

                case "response.done":
                    // 响应完成，这是 Qwen TTS API 的最终事件。
                    String responseId = responseIdRef.get();
                    log.debug("TTS response completed - responseId: {}", responseId);
                    synthesisLatch.countDown();
                    break;

                case "error":
                    // 错误事件。
                    if (message.has("error")) {
                        var errorElement = message.get("error");
                        String errorType = "unknown";
                        String errorCode = "unknown";
                        String errorMessage = "Unknown error";

                        if (errorElement.isJsonObject()) {
                            JsonObject errorObj = errorElement.getAsJsonObject();
                            errorType = errorObj.has("type") ? errorObj.get("type").getAsString() : "unknown";
                            errorCode = errorObj.has("code") ? errorObj.get("code").getAsString() : "unknown";
                            errorMessage = errorObj.has("message") ? errorObj.get("message").getAsString() : "Unknown error";
                        } else {
                            errorMessage = errorElement.toString();
                        }

                        String fullErrorMessage = String.format("TTS Error [%s/%s]: %s", errorType, errorCode, errorMessage);
                        log.error("{}", fullErrorMessage);

                        errorRef.set(new IllegalStateException(fullErrorMessage));
                        synthesisLatch.countDown();
                    }
                    break;

                default:
                    log.trace("Unhandled TTS event type: {}", eventType);
            }

        } catch (Exception e) {
            log.error("Error processing TTS server event", e);
            errorRef.set(e);
            synthesisLatch.countDown();
        }
    }

    /**
     * 高效收集音频分片的内部类。
     * 使用 ByteArrayOutputStream 获得摊还 O(1) 的追加性能，
     * 避免手动扩容数组导致 O(n²) 拷贝。
     */
    private static class ByteArrayContainer {
        private final java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();

        public synchronized void append(byte[] chunk) {
            baos.write(chunk, 0, chunk.length);
        }

        public synchronized byte[] toByteArray() {
            return baos.toByteArray();
        }
    }

    // 配置 setter，供测试或运行时配置注入使用。

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

    private TtsConfigSnapshot resolveConfig(String userId) {
        return userId == null || userId.isBlank()
                ? systemConfigSnapshot()
                : settingsResolver.resolveTts(userId);
    }

    private TtsConfigSnapshot systemConfigSnapshot() {
        return new TtsConfigSnapshot(
                model,
                apiKey,
                voice,
                format,
                sampleRate,
                mode,
                languageType,
                speechRate,
                volume
        );
    }

    private boolean isConfigured(TtsConfigSnapshot config) {
        return config != null && config.apiKey() != null && !config.apiKey().trim().isEmpty();
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() || trimmed.contains("${") ? null : trimmed;
    }

    public void setVoice(String voice) {
        this.voice = voice;
    }

    public void setFormat(String format) {
        this.format = format;
    }

    public void setSampleRate(Integer sampleRate) {
        this.sampleRate = sampleRate;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public void setLanguageType(String languageType) {
        this.languageType = languageType;
    }

    public void setSpeechRate(Float speechRate) {
        this.speechRate = speechRate;
    }

    public void setVolume(Integer volume) {
        this.volume = volume;
    }
}
