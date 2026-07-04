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
 * Qwen实时语音合成服务（基于WebSocket）
 * <p>
 * 通过阿里云DashScope的qwen-tts-realtime模型和WebSocket API
 * 提供实时文本转语音合成能力。
 * </p>
 * <p>
 * 核心特性：
 * - 基于WebSocket的实时语音合成，每次合成创建独立连接
 * - 使用commit模式，由调用方手动控制提交时机
 * - 同步合成接口内置30秒超时保护
 * - 通过response.audio.delta事件自动收集音频分片
 * - 支持中文语音，可配置音色、语速和音量
 * - 支持用户级配置覆盖，未登录时使用系统默认配置
 * </p>
 */
@Slf4j
@Service
public class QwenTtsService {

    private final AiProperties aiProperties;
    private final AiSettingsResolver settingsResolver;

    private String model;
    private String apiKey;
    private String voice;
    private String format;
    private Integer sampleRate;
    private String mode;
    private String languageType;
    private Float speechRate;
    private Integer volume;

    /**
     * 构造函数，从语音面试配置中加载默认TTS参数
     */
    public QwenTtsService(
            VoiceInterviewProperties voiceInterviewProperties,
            AiProperties aiProperties,
            AiSettingsResolver settingsResolver) {
        this.aiProperties = aiProperties;
        this.settingsResolver = settingsResolver;
        applyTtsConfig(voiceInterviewProperties.getQwen().getTts());
    }

    /**
     * 重载TTS配置
     * 用于配置热更新场景
     */
    public void reload(VoiceInterviewProperties voiceInterviewProperties) {
        applyTtsConfig(voiceInterviewProperties.getQwen().getTts());
        log.info("语音合成服务已重载: model={}, voice={}", model, voice);
    }

    /**
     * 应用TTS配置参数
     */
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

    /**
     * 检查服务是否已配置API密钥
     */
    public boolean isConfigured() {
        return apiKey != null && !apiKey.trim().isEmpty();
    }

    /**
     * 服务初始化
     * 检查API密钥配置状态
     */
    @PostConstruct
    public void init() {
        if (!isConfigured()) {
            log.warn("语音合成服务初始化时未配置API密钥，在配置完成前将保持禁用状态");
            return;
        }
        log.info("语音合成服务已初始化: model={}, voice={}, sampleRate={}Hz",
                model, voice, sampleRate);
    }

    /**
     * 将文本合成为语音音频（不指定用户ID，使用系统默认配置）
     *
     * @param text 待合成文本，null或空字符串返回空数组
     * @return PCM音频数据（24kHz、16-bit、单声道），合成失败时返回空数组
     */
    public byte[] synthesize(String text) {
        return synthesize(text, CurrentUserContext.getCurrentUserIdOrNull());
    }

    /**
     * 将文本合成为语音音频（指定用户ID，使用用户级配置）
     * <p>
     * 使用DashScope基于WebSocket的TTS API同步生成PCM音频，
     * 建立WebSocket连接后发送文本，通过CountDownLatch等待合成完成，
     * 设置30秒超时避免无限阻塞。
     * </p>
     *
     * @param text 待合成文本
     * @param userId 用户ID，为null时使用系统默认配置
     * @return PCM音频数据，合成失败时返回空数组
     */
    public byte[] synthesize(String text, String userId) {
        TtsConfigSnapshot configSnapshot = resolveConfig(userId);
        // 处理空文本
        if (text == null || text.trim().isEmpty()) {
            log.debug("文本为空，返回空音频数组");
            return new byte[0];
        }
        if (!isConfigured(configSnapshot)) {
            log.warn("语音合成已跳过：API密钥未配置");
            return new byte[0];
        }

        log.debug("开始语音合成，文本长度：{} 字符", text.length());

        // 同步等待锁存器
        CountDownLatch synthesisLatch = new CountDownLatch(1);
        // 音频数据收集容器
        ByteArrayContainer audioContainer = new ByteArrayContainer();
        // 错误跟踪容器
        AtomicReference<Throwable> errorRef = new AtomicReference<>();
        // 响应ID跟踪容器
        AtomicReference<String> responseIdRef = new AtomicReference<>();

        try {
            // 构造WebSocket连接参数
            QwenTtsRealtimeParam param = QwenTtsRealtimeParam.builder()
                    .model(configSnapshot.model())
                    .apikey(configSnapshot.apiKey())
                    .build();

            // 创建WebSocket事件回调处理器
            QwenTtsRealtimeCallback callback = new QwenTtsRealtimeCallback() {
                @Override
                public void onOpen() {
                    log.debug("语音合成WebSocket连接已建立");
                }

                @Override
                public void onEvent(JsonObject message) {
                    handleServerEvent(message, audioContainer, synthesisLatch, errorRef, responseIdRef);
                }

                @Override
                public void onClose(int code, String reason) {
                    log.debug("语音合成WebSocket已关闭 - code: {}, reason: {}", code, reason);
                    synthesisLatch.countDown();
                }
            };

            // 创建语音合成实例并连接
            QwenTtsRealtime qwenTtsRealtime = new QwenTtsRealtime(param, callback);

            try {
                // 连接服务端（阻塞调用）
                qwenTtsRealtime.connect();

                // 配置语音合成会话参数
                QwenTtsRealtimeConfig config = QwenTtsRealtimeConfig.builder()
                        .voice(configSnapshot.voice())
                        .responseFormat(getAudioFormat())
                        .mode(configSnapshot.mode())
                        .languageType(configSnapshot.languageType())
                        .speechRate(configSnapshot.speechRate())
                        .volume(configSnapshot.volume())
                        .build();

                // 更新会话配置
                qwenTtsRealtime.updateSession(config);

                log.info("[TTS] 会话已配置，音色: {}，正在合成文本（长度: {}）",
                        configSnapshot.voice(), text.length());

                // 使用commit模式发送待合成文本
                qwenTtsRealtime.appendText(text);
                qwenTtsRealtime.commit();

                log.info("[TTS] 文本已发送到语音合成服务，等待音频响应...");

                // 等待合成完成，30秒超时
                boolean completed = synthesisLatch.await(30, TimeUnit.SECONDS);

                if (!completed) {
                    log.error("语音合成超时（30秒）");
                    return new byte[0];
                }

                // 检查是否发生错误
                Throwable error = errorRef.get();
                if (error != null) {
                    log.error("语音合成失败", error);
                    return new byte[0];
                }

                // 返回收集到的音频数据
                byte[] audioData = audioContainer.toByteArray();
                log.info("[TTS] 合成成功完成 - {} 字节音频数据, responseId: {}",
                        audioData.length, responseIdRef.get());

                return audioData;

            } finally {
                // 确保连接被关闭
                try {
                    qwenTtsRealtime.close();
                } catch (Exception e) {
                    log.error("关闭语音合成连接时出错", e);
                }
            }

        } catch (InterruptedException e) {
            log.error("语音合成被中断", e);
            Thread.currentThread().interrupt();
            return new byte[0];
        } catch (Exception e) {
            log.error("文本合成失败", e);
            return new byte[0];
        }
    }

    /**
     * 获取语音合成的音频格式
     * 当前固定使用24kHz PCM单声道16-bit格式
     */
    private QwenTtsRealtimeAudioFormat getAudioFormat() {
        return QwenTtsRealtimeAudioFormat.PCM_24000HZ_MONO_16BIT;
    }

    /**
     * 服务销毁时清理资源
     * 每次合成都创建独立临时连接，无持久资源需要清理
     */
    @PreDestroy
    public void destroy() {
        log.info("语音合成服务已销毁");
    }

    /**
     * 处理DashScope语音合成服务端事件
     * <p>
     * 主要处理以下事件：
     * - session.created：服务端会话已创建
     * - session.updated：会话配置已更新
     * - response.audio.delta：收到音频分片（base64编码），解码后收集
     * - response.done：响应已完成，触发锁存器
     * - error：发生错误，记录错误并触发锁存器
     * </p>
     */
    private void handleServerEvent(JsonObject message, ByteArrayContainer audioContainer,
                                   CountDownLatch synthesisLatch, AtomicReference<Throwable> errorRef,
                                   AtomicReference<String> responseIdRef) {
        try {
            String eventType = message.get("type").getAsString();

            if (log.isTraceEnabled()) {
                log.trace("收到语音合成事件: {}, 完整消息: {}", eventType, message);
            } else {
                log.debug("收到语音合成事件: {}", eventType);
            }

            switch (eventType) {
                case "session.created":
                    String sessionId = message.has("session") && message.get("session").isJsonObject()
                            ? message.get("session").getAsJsonObject().get("id").getAsString()
                            : "unknown";
                    log.debug("语音合成会话已创建: {}", sessionId);
                    break;

                case "session.updated":
                    log.debug("语音合成会话配置已更新");
                    break;

                case "response.audio.delta":
                    // 收到音频分片，delta字段为base64编码的音频数据
                    if (message.has("delta")) {
                        String audioBase64 = message.get("delta").getAsString();
                        if (audioBase64 != null && !audioBase64.isEmpty()) {
                            byte[] audioChunk = Base64.getDecoder().decode(audioBase64);
                            audioContainer.append(audioChunk);
                            log.trace("收到音频分片 - {} 字节", audioChunk.length);
                        }
                    }
                    break;

                case "response.done":
                    // 响应完成，触发锁存器
                    String responseId = responseIdRef.get();
                    log.debug("语音合成响应已完成 - responseId: {}", responseId);
                    synthesisLatch.countDown();
                    break;

                case "error":
                    // 错误事件，解析错误详情
                    if (message.has("error")) {
                        var errorElement = message.get("error");
                        String errorType = "unknown";
                        String errorCode = "unknown";
                        String errorMessage = "未知错误";

                        if (errorElement.isJsonObject()) {
                            JsonObject errorObj = errorElement.getAsJsonObject();
                            errorType = errorObj.has("type") ? errorObj.get("type").getAsString() : "unknown";
                            errorCode = errorObj.has("code") ? errorObj.get("code").getAsString() : "unknown";
                            errorMessage = errorObj.has("message") ? errorObj.get("message").getAsString() : "未知错误";
                        } else {
                            errorMessage = errorElement.toString();
                        }

                        String fullErrorMessage = String.format("语音合成错误 [%s/%s]: %s", errorType, errorCode, errorMessage);
                        log.error("{}", fullErrorMessage);

                        errorRef.set(new IllegalStateException(fullErrorMessage));
                        synthesisLatch.countDown();
                    }
                    break;

                default:
                    log.trace("未处理的语音合成事件类型: {}", eventType);
            }

        } catch (Exception e) {
            log.error("处理语音合成服务端事件时出错", e);
            errorRef.set(e);
            synthesisLatch.countDown();
        }
    }

    /**
     * 高效收集音频分片的内部类
     * 使用ByteArrayOutputStream获得摊还O(1)的追加性能，
     * 避免手动扩容数组导致O(n²)拷贝
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

    // 配置setter，供测试或运行时配置注入使用

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
     * 根据用户ID解析语音合成配置
     * 未登录时使用系统默认配置
     */
    private TtsConfigSnapshot resolveConfig(String userId) {
        return userId == null || userId.isBlank()
                ? systemConfigSnapshot()
                : settingsResolver.resolveTts(userId);
    }

    /**
     * 构建系统默认配置快照
     */
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

    /**
     * 检查配置快照是否已配置API密钥
     */
    private boolean isConfigured(TtsConfigSnapshot config) {
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

}