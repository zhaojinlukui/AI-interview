package interview.guide.modules.voiceinterview.service;

import interview.guide.common.ai.AiClientFactory;
import interview.guide.common.ai.PromptSanitizer;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.resume.model.ResumeEntity;
import interview.guide.modules.resume.repository.ResumeRepository;
import interview.guide.modules.voiceinterview.config.VoiceInterviewProperties;
import interview.guide.modules.voiceinterview.model.VoiceInterviewSessionEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * 语音面试大模型对话服务
 * 负责语音面试场景下的AI对话生成，支持非流式和流式两种模式，
 * 流式模式按句子边界拆分输出，适配语音合成管线，
 * 包含语音优化、错误分类和提示词安全处理
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class VoiceLlmService {

    private static final String TERMINAL_PUNCTUATION = "。！？；!?;."; // 句子终止标点符号

    private final AiClientFactory aiClientFactory;
    private final VoiceInterviewPromptService promptService;
    private final ResumeRepository resumeRepository;
    private final VoiceInterviewProperties voiceInterviewProperties;
    private final PromptSanitizer promptSanitizer;

    /**
     * 非流式对话
     * 一次性生成完整回复并优化为适合语音播报的格式
     */
    public String chat(String userInput, VoiceInterviewSessionEntity session, List<String> conversationHistory) {
        try {
            PromptContext promptContext = buildPromptContext(userInput, session, conversationHistory);

            ChatClient chatClient = aiClientFactory.getVoiceChatClient(session.getUserId());

            ChatClient.CallResponseSpec response = chatClient.prompt()
                    .system(promptContext.systemPrompt())
                    .user(promptContext.userPrompt())
                    .call();

            String content = response.chatResponse().getResult().getOutput().getText();
            // 优化为适合语音播报的格式
            String optimized = optimizeForVoice(content);

            log.info("大模型回复已生成，会话ID: {}，内容: {}",
                    session.getId(),
                    optimized.substring(0, Math.min(100, optimized.length())));

            return optimized;

        } catch (Exception e) {
            BusinessException businessException = mapLlmErrorToBusinessException(e);
            log.error("大模型对话错误，会话ID: {}: {}",
                    session.getId(), businessException.getMessage(), e);
            throw businessException;
        }
    }

    /**
     * 流式对话（按句子拆分）
     * 每检测到一个完整句子就回调onSentence用于语音合成，
     * 同时通过onToken推送实时文本用于前端显示
     */
    public String chatStreamSentences(String userInput,
                                      Consumer<String> onToken,
                                      Consumer<String> onSentence,
                                      VoiceInterviewSessionEntity session,
                                      List<String> conversationHistory) {
        try {
            PromptContext promptContext = buildPromptContext(userInput, session, conversationHistory);
            ChatClient chatClient = aiClientFactory.getVoiceChatClient(session.getUserId());
            StringBuilder raw = new StringBuilder(); // 累积的原始文本
            AtomicLong lastEmitNanos = new AtomicLong(System.nanoTime()); // 上次推送时间
            AtomicInteger lastEmitLength = new AtomicInteger(0); // 上次推送时的文本长度
            AtomicInteger lastSentenceEnd = new AtomicInteger(0); // 上次句子结束位置
            int emitIntervalMs = Math.max(80, voiceInterviewProperties.getAiStreamPushIntervalMs()); // 推送间隔
            int minCharsDelta = Math.max(4, voiceInterviewProperties.getAiStreamMinCharsDelta()); // 最小推送字符增量

            chatClient.prompt()
                    .system(promptContext.systemPrompt())
                    .user(promptContext.userPrompt())
                    .stream()
                    .content()
                    .doOnNext(token -> {
                        if (token == null || token.isEmpty()) {
                            return;
                        }
                        raw.append(token);

                        // 检测句子边界，发现完整句子时回调onSentence
                        if (onSentence != null && hasTerminalSince(token)) {
                            String normalized = normalizeRealtimeText(raw.toString());
                            int currentEnd = normalized.length();
                            if (currentEnd > lastSentenceEnd.get()) {
                                String sentence = normalized.substring(lastSentenceEnd.get()).trim();
                                if (!sentence.isEmpty()) {
                                    onSentence.accept(sentence);
                                }
                                lastSentenceEnd.set(currentEnd);
                            }
                        }

                        // 实时文本推送（按间隔和增量节流）
                        if (onToken == null) {
                            return;
                        }
                        long now = System.nanoTime();
                        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(now - lastEmitNanos.get());
                        int currentLength = raw.length();
                        boolean shouldEmit = elapsedMs >= emitIntervalMs && currentLength - lastEmitLength.get() >= minCharsDelta;
                        if (!shouldEmit) {
                            return;
                        }
                        String normalized = normalizeRealtimeText(raw.toString());
                        if (normalized.isBlank()) {
                            return;
                        }
                        onToken.accept(normalized);
                        lastEmitNanos.set(now);
                        lastEmitLength.set(normalized.length());
                    })
                    .blockLast();

            // 发送最后一段剩余文本（可能不以终止标点结尾）
            if (onSentence != null) {
                String normalized = normalizeRealtimeText(raw.toString());
                if (normalized.length() > lastSentenceEnd.get()) {
                    String remaining = normalized.substring(lastSentenceEnd.get()).trim();
                    if (!remaining.isEmpty()) {
                        onSentence.accept(remaining);
                    }
                }
            }

            // 优化完整回复并推送最终文本
            String optimized = optimizeForVoice(raw.toString());
            if (onToken != null && !optimized.isBlank()) {
                onToken.accept(optimized);
            }

            log.info("大模型句子流式回复，会话ID: {}，内容: {}",
                    session.getId(),
                    optimized.substring(0, Math.min(100, optimized.length())));
            return optimized;
        } catch (Exception e) {
            BusinessException businessException = mapLlmErrorToBusinessException(e);
            log.error("大模型句子流式错误，会话ID: {}: {}",
                    session.getId(), businessException.getMessage(), e);
            throw businessException;
        }
    }

    /**
     * 构建提示词上下文
     * 包含系统提示词（含简历信息）和对话历史
     */
    private PromptContext buildPromptContext(String userInput, VoiceInterviewSessionEntity session, List<String> conversationHistory) {
        // 加载关联的简历文本
        String resumeText = null;
        if (session.getResumeId() != null) {
            ResumeEntity resume = resumeRepository.findByIdAndUserId(
                    session.getResumeId(),
                    session.getUserId()
            ).orElse(null);
            if (resume != null) {
                resumeText = resume.getResumeText();
            }
        }

        String systemPrompt = promptService.generateSystemPromptWithContext(session.getSkillId(), resumeText);

        // 拼接对话历史和当前用户输入
        StringBuilder promptBuilder = new StringBuilder();
        if (conversationHistory != null && !conversationHistory.isEmpty()) {
            promptBuilder.append("【之前的对话】\n");
            for (String message : conversationHistory) {
                promptBuilder.append(promptSanitizer.sanitize(message)).append("\n");
            }
            promptBuilder.append("\n【当前对话】\n");
        }
        promptBuilder.append("用户：").append(
                promptSanitizer.wrapWithDelimiters("input", promptSanitizer.sanitize(userInput)));
        return new PromptContext(systemPrompt, promptBuilder.toString());
    }

    /**
     * 将AI调用异常映射为业务异常
     * 识别401/403认证失败、超时、限流、模型不存在和网络连接失败等场景
     */
    private BusinessException mapLlmErrorToBusinessException(Exception e) {
        if (e instanceof BusinessException businessException) {
            return businessException;
        }

        String errorMessage = collectErrorMessages(e);
        if (containsIgnoreCase(errorMessage, "401", "403", "access_denied", "authentication",
                "unauthorized")) {
            return new BusinessException(ErrorCode.AI_API_KEY_INVALID,
                    "AI 服务认证失败，请检查 API Key 配置", e);
        }
        if (containsIgnoreCase(errorMessage, "timeout", "timed out")) {
            return new BusinessException(ErrorCode.AI_SERVICE_TIMEOUT,
                    "AI 服务响应超时，请稍后重试", e);
        }
        if (containsIgnoreCase(errorMessage, "429", "rate limit", "quota")) {
            return new BusinessException(ErrorCode.AI_RATE_LIMIT_EXCEEDED,
                    "AI 服务调用频率超限，请稍后重试", e);
        }
        if (containsIgnoreCase(errorMessage, "model not found", "model does not exist",
                "invalid model", "404")) {
            return new BusinessException(ErrorCode.AI_SERVICE_ERROR,
                    "AI 模型调用失败，请检查模型名称或稍后重试", e);
        }
        if (containsIgnoreCase(errorMessage, "connection", "network", "connect refused",
                "connection refused")) {
            return new BusinessException(ErrorCode.AI_SERVICE_UNAVAILABLE,
                    "AI 服务网络连接失败，请检查网络", e);
        }
        return new BusinessException(ErrorCode.AI_SERVICE_UNAVAILABLE,
                "抱歉，AI 服务暂时不可用，请稍后重试", e);
    }

    /**
     * 收集异常链中的所有错误消息
     */
    private String collectErrorMessages(Throwable throwable) {
        StringBuilder builder = new StringBuilder();
        Throwable current = throwable;
        while (current != null) {
            String message = current.getMessage();
            if (message != null && !message.isBlank()) {
                if (!builder.isEmpty()) {
                    builder.append(" | ");
                }
                builder.append(message);
            }
            current = current.getCause();
        }
        return builder.toString();
    }

    /**
     * 不区分大小写检查文本是否包含指定关键词
     */
    private boolean containsIgnoreCase(String text, String... candidates) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String lowerText = text.toLowerCase();
        for (String candidate : candidates) {
            if (lowerText.contains(candidate.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 优化文本为适合语音播报的格式
     * 去除Markdown标记，限制最大长度并在句子边界截断
     */
    private String optimizeForVoice(String content) {
        String normalized = normalizeRealtimeText(content);
        if (normalized.isBlank()) {
            return "请继续。";
        }

        int maxChars = Math.max(80, voiceInterviewProperties.getAiQuestionMaxChars());
        if (normalized.length() <= maxChars) {
            return normalized;
        }

        // 在最大长度内寻找最后一个句子终止标点
        String truncated = normalized.substring(0, maxChars);
        int lastTerminal = -1;
        for (int i = truncated.length() - 1; i >= 0; i--) {
            if (TERMINAL_PUNCTUATION.indexOf(truncated.charAt(i)) >= 0) {
                lastTerminal = i;
                break;
            }
        }
        // 如果在后半段找到终止标点，则在该处截断
        if (lastTerminal >= maxChars / 2) {
            return truncated.substring(0, lastTerminal + 1);
        }

        // 未找到合适断点，使用省略号
        return truncated + "…";
    }

    /**
     * 规范化实时文本
     * 移除Markdown格式标记和多余空白
     */
    private String normalizeRealtimeText(String content) {
        if (content == null || content.isBlank()) {
            return "";
        }
        return content
                .replace("**", "")
                .replace("```", "")
                .replace("`", "")
                .replaceAll("(?m)^\\s*[-*+]\\s*", "") // 移除Markdown列表标记
                .replaceAll("\\s+", " ") // 合并连续空白为单个空格
                .trim();
    }

    /**
     * 检查token中是否包含句子终止标点
     */
    private boolean hasTerminalSince(String token) {
        for (int i = 0; i < token.length(); i++) {
            if (TERMINAL_PUNCTUATION.indexOf(token.charAt(i)) >= 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * 提示词上下文记录
     */
    private record PromptContext(String systemPrompt, String userPrompt) {}
}