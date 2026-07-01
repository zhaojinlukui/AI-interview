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

@Service
@Slf4j
@RequiredArgsConstructor
public class VoiceLlmService {

    private static final String TERMINAL_PUNCTUATION = "。！？；!?;.";

    private final AiClientFactory aiClientFactory;
    private final VoiceInterviewPromptService promptService;
    private final ResumeRepository resumeRepository;
    private final VoiceInterviewProperties voiceInterviewProperties;
    private final PromptSanitizer promptSanitizer;

    public String chat(String userInput, VoiceInterviewSessionEntity session, List<String> conversationHistory) {
        try {
            PromptContext promptContext = buildPromptContext(userInput, session, conversationHistory);

            ChatClient chatClient = aiClientFactory.getVoiceChatClient(session.getUserId());

            ChatClient.CallResponseSpec response = chatClient.prompt()
                .system(promptContext.systemPrompt())
                .user(promptContext.userPrompt())
                .call();

            String content = response.chatResponse().getResult().getOutput().getText();
            String optimized = optimizeForVoice(content);

            log.info("LLM response generated for session {}: {}", session.getId(),
                     optimized.substring(0, Math.min(100, optimized.length())));

            return optimized;

        } catch (Exception e) {
            BusinessException businessException = mapLlmErrorToBusinessException(e);
            log.error("LLM chat error for session {}: {}", session.getId(),
                businessException.getMessage(), e);
            throw businessException;
        }
    }

    public String chatStream(String userInput, Consumer<String> onToken, VoiceInterviewSessionEntity session, List<String> conversationHistory) {
        return chatStreamSentences(userInput, onToken, null, session, conversationHistory);
    }

    /**
     * 流式调用 LLM，每检测到一个完整句子就回调 onSentence，同时推送实时文本给 onToken。
     * 返回完整优化后的文本。
     */
    public String chatStreamSentences(String userInput,
                                       Consumer<String> onToken,
                                       Consumer<String> onSentence,
                                       VoiceInterviewSessionEntity session,
                                       List<String> conversationHistory) {
        try {
            PromptContext promptContext = buildPromptContext(userInput, session, conversationHistory);
            ChatClient chatClient = aiClientFactory.getVoiceChatClient(session.getUserId());
            StringBuilder raw = new StringBuilder();
            AtomicLong lastEmitNanos = new AtomicLong(System.nanoTime());
            AtomicInteger lastEmitLength = new AtomicInteger(0);
            AtomicInteger lastSentenceEnd = new AtomicInteger(0);
            int emitIntervalMs = Math.max(80, voiceInterviewProperties.getAiStreamPushIntervalMs());
            int minCharsDelta = Math.max(4, voiceInterviewProperties.getAiStreamMinCharsDelta());

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

                    // 检测句子边界，回调 onSentence
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

                    // 实时文本推送
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

            // 发送最后一段（可能不以终止标点结尾）
            if (onSentence != null) {
                String normalized = normalizeRealtimeText(raw.toString());
                if (normalized.length() > lastSentenceEnd.get()) {
                    String remaining = normalized.substring(lastSentenceEnd.get()).trim();
                    if (!remaining.isEmpty()) {
                        onSentence.accept(remaining);
                    }
                }
            }

            String optimized = optimizeForVoice(raw.toString());
            if (onToken != null && !optimized.isBlank()) {
                onToken.accept(optimized);
            }

            log.info("LLM sentence stream response for session {}: {}", session.getId(),
                optimized.substring(0, Math.min(100, optimized.length())));
            return optimized;
        } catch (Exception e) {
            BusinessException businessException = mapLlmErrorToBusinessException(e);
            log.error("LLM sentence stream error for session {}: {}", session.getId(),
                businessException.getMessage(), e);
            throw businessException;
        }
    }

    private PromptContext buildPromptContext(String userInput, VoiceInterviewSessionEntity session, List<String> conversationHistory) {
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

    private String optimizeForVoice(String content) {
        String normalized = normalizeRealtimeText(content);
        if (normalized.isBlank()) {
            return "请继续。";
        }

        int maxChars = Math.max(80, voiceInterviewProperties.getAiQuestionMaxChars());
        if (normalized.length() <= maxChars) {
            return normalized;
        }

        String truncated = normalized.substring(0, maxChars);
        int lastTerminal = -1;
        for (int i = truncated.length() - 1; i >= 0; i--) {
            if (TERMINAL_PUNCTUATION.indexOf(truncated.charAt(i)) >= 0) {
                lastTerminal = i;
                break;
            }
        }
        if (lastTerminal >= maxChars / 2) {
            return truncated.substring(0, lastTerminal + 1);
        }

        return truncated + "…";
    }

    private String normalizeRealtimeText(String content) {
        if (content == null || content.isBlank()) {
            return "";
        }
        return content
            .replace("**", "")
            .replace("```", "")
            .replace("`", "")
            .replaceAll("(?m)^\\s*[-*+]\\s*", "")
            .replaceAll("\\s+", " ")
            .trim();
    }

    private boolean hasTerminalSince(String token) {
        for (int i = 0; i < token.length(); i++) {
            if (TERMINAL_PUNCTUATION.indexOf(token.charAt(i)) >= 0) {
                return true;
            }
        }
        return false;
    }

    private record PromptContext(String systemPrompt, String userPrompt) {}
}
