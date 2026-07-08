package interview.guide.common.ai;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import org.slf4j.Logger;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 统一封装结构化输出调用与重试策略。
 */
@Component
public class StructuredOutputInvoker {

    // 重试时添加的额外提示词
    private static final String STRICT_JSON_INSTRUCTION = """
请仅返回可被 JSON 解析器直接解析的 JSON 对象，并严格满足字段结构要求：
1) 不要输出 Markdown 代码块（如 ```json）。
2) 不要输出任何解释文字、前后缀、注释。
3) 所有字符串内引号必须正确转义。
4) 不要输出 function-name/arguments/name/parameters/tool_call 等函数调用包装结构。
    """;

    // ========== 指标常量 ==========
    private static final String METRIC_INVOCATIONS = "app.ai.structured_output.invocations";  // 调用总次数
    private static final String METRIC_ATTEMPTS = "app.ai.structured_output.attempts";        // 尝试次数（包括重试）
    private static final String METRIC_LATENCY = "app.ai.structured_output.latency";          // 调用延迟
    private static final String STATUS_SUCCESS = "success";                                   // 成功状态
    private static final String STATUS_FAILURE = "failure";                                   // 失败状态
    private static final int MAX_CONTEXT_TAG_LENGTH = 48;                                     // 上下文标签最大长度

    // 用于规范化标签的正则表达式
    private static final Pattern NON_ALNUM_PATTERN = Pattern.compile("[^a-z0-9_]+");    // 非字母数字下划线字符
    private static final Pattern MULTI_UNDERSCORE = Pattern.compile("_+");              // 连续下划线

    // ========== 配置参数 ==========
    private final int maxAttempts;                           // 最大尝试次数（包括首次）
    private final boolean includeLastErrorInRetryPrompt;     // 重试时是否包含上次错误信息
    private final boolean retryUseRepairPrompt;              // 重试时是否使用修复提示
    private final boolean retryAppendStrictJsonInstruction;  // 重试时是否附加严格JSON指令
    private final int errorMessageMaxLength;                 // 错误信息最大长度
    private final boolean metricsEnabled;                    // 是否启用指标收集
    private final MeterRegistry meterRegistry;               // Micrometer指标注册器

    // 构造函数，初始化配置参数
    public StructuredOutputInvoker(
        StructuredOutputProperties properties,
        @Autowired(required = false) MeterRegistry meterRegistry
    ) {
        this.maxAttempts = Math.max(1, properties.getStructuredMaxAttempts());
        this.includeLastErrorInRetryPrompt = properties.isStructuredIncludeLastError();
        this.retryUseRepairPrompt = properties.isStructuredRetryUseRepairPrompt();
        this.retryAppendStrictJsonInstruction = properties.isStructuredRetryAppendStrictJsonInstruction();
        this.errorMessageMaxLength = Math.max(20, properties.getStructuredErrorMessageMaxLength());
        this.metricsEnabled = properties.isStructuredMetricsEnabled();
        this.meterRegistry = meterRegistry;
    }

    // 执行结构化输出调用的主方法
    public <T> T invoke(
        ChatClient chatClient,
        String systemPromptWithFormat,
        String userPrompt,
        BeanOutputConverter<T> outputConverter,
        ErrorCode errorCode,
        String errorPrefix,
        String logContext,
        Logger log
    ) {
        return invoke(
            chatClient,
            systemPromptWithFormat,
            userPrompt,
            outputConverter,
            null,
            errorCode,
            errorPrefix,
            logContext,
            log
        );
    }

    public <T> T invoke(
        ChatClient chatClient,
        String systemPromptWithFormat,
        String userPrompt,
        BeanOutputConverter<T> outputConverter,
        ChatOptions chatOptions,
        ErrorCode errorCode,
        String errorPrefix,
        String logContext,
        Logger log
    ) {
        long startNanos = System.nanoTime();
        String contextTag = normalizeContextTag(logContext);  // 规范化上下文标签
        String securedSystemPrompt = systemPromptWithFormat
            + PromptSecurityConstants.ANTI_INJECTION_INSTRUCTION;
        Exception lastError = null;
        // 重试循环
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            // 加载提示词，第一次使用原始提示词
            String attemptSystemPrompt = attempt == 1
                ? securedSystemPrompt
                : buildRetrySystemPrompt(securedSystemPrompt, lastError);
            try {
                // 调用AI模型获取响应内容
                ChatClient.ChatClientRequestSpec requestSpec = chatClient.prompt()
                    .system(attemptSystemPrompt)
                    .user(userPrompt);
                if (chatOptions != null) {
                    requestSpec = requestSpec.options(chatOptions);
                }
                String content = requestSpec.call().content();
                // 尝试转换
                T result = convertWithRepair(content, outputConverter, logContext, log);
                // 记录成功指标
                recordAttempt(contextTag, STATUS_SUCCESS);
                recordInvocation(contextTag, STATUS_SUCCESS, startNanos);
                return result;
            } catch (Exception e) {
                lastError = e;
                recordAttempt(contextTag, STATUS_FAILURE); // 记录失败尝试
                if (attempt < maxAttempts) {
                    log.warn("{}结构化解析失败，准备重试: attempt={}/{}, error={}",
                        logContext, attempt, maxAttempts, e.getMessage());
                } else {
                    log.error("{}结构化解析失败，已达最大重试次数: attempts={}, error={}",
                        logContext, maxAttempts, e.getMessage());
                }
            }
        }

        // 所有重试都失败，记录指标并抛出业务异常
        recordInvocation(contextTag, STATUS_FAILURE, startNanos);
        throw new BusinessException(
            errorCode,
            errorPrefix + (lastError != null ? lastError.getMessage() : "unknown")
        );
    }

    // 尝试转换
    private <T> T convertWithRepair(
        String content,
        BeanOutputConverter<T> outputConverter,
        String logContext,
        Logger log
    ) {
        rejectFunctionCallEnvelope(content);
        try {
            // 首先尝试直接转换
            return outputConverter.convert(content);  //将Json字符串反序列化为指定Java对象
        } catch (Exception firstError) {
            // 转换失败，修复JSON中未转义引号
            String repaired = repairUnescapedQuotesInJsonStrings(content);
            // 返回内容发生变化说明修复成功
            if (!repaired.equals(content)) {
                try {
                    // 转换
                    T result = outputConverter.convert(repaired);
                    log.warn("{}结构化 JSON 存在未转义引号，已在本地修复后解析成功", logContext);
                    return result;
                } catch (Exception repairError) {
                    // 将修复异常附加到原始异常
                    firstError.addSuppressed(repairError);
                }
            }
            // 失败
            throw firstError;
        }
    }

    private void rejectFunctionCallEnvelope(String content) {
        if (content == null || content.isBlank()) {
            return;
        }
        String lowerContent = content.toLowerCase(Locale.ROOT);
        boolean functionEnvelope = lowerContent.contains("\"function-name\"")
            || lowerContent.contains("\"function_name\"")
            || lowerContent.contains("\"function\"")
            || lowerContent.contains("\"tool_call\"")
            || lowerContent.contains("\"tool_calls\"")
            || lowerContent.contains("\"parameters\"");
        if ((functionEnvelope || lowerContent.contains("\"name\""))
            && lowerContent.contains("\"arguments\"")) {
            throw new IllegalArgumentException(
                "模型返回了一个函数调用信封，而不是所需的 JSON 对象"
            );
        }
    }

    // 修复JSON中未转义引号
    private String repairUnescapedQuotesInJsonStrings(String content) {
        if (content == null || content.isBlank()) {
            return content;
        }
        StringBuilder repaired = new StringBuilder(content.length() + 16);
        boolean inString = false;
        boolean escaping = false;
        for (int i = 0; i < content.length(); i++) {
            char ch = content.charAt(i);
            if (!inString) {
                if (ch == '"') {
                    inString = true;
                }
                repaired.append(ch);
                continue;
            }

            if (escaping) {
                repaired.append(ch);
                escaping = false;
                continue;
            }
            if (ch == '\\') {                                                                       
                repaired.append(ch);
                escaping = true;
                continue;
            }
            if (ch == '"') {
                if (isLikelyJsonStringTerminator(content, i + 1)) {
                    inString = false;
                    repaired.append(ch);
                } else {
                    repaired.append("\\\"");
                }
                continue;
            }
            repaired.append(ch);
        }
        return repaired.toString();
    }

    // 判断当前位置的双引号是否可能是JSON字符串的结束符
    private boolean isLikelyJsonStringTerminator(String content, int start) {
        for (int i = start; i < content.length(); i++) {
            char next = content.charAt(i);
            if (Character.isWhitespace(next)) {
                continue;
            }
            return next == ',' || next == '}' || next == ']' || next == ':';
        }
        return true;
    }

    // 构建错误后新提示词
    private String buildRetrySystemPrompt(String systemPromptWithFormat, Exception lastError) {
        if (!retryUseRepairPrompt) {
            return systemPromptWithFormat;
        }

        StringBuilder prompt = new StringBuilder(systemPromptWithFormat)
            .append("\n\n");
        // 附加Json指令
        if (retryAppendStrictJsonInstruction) {
            prompt.append(STRICT_JSON_INSTRUCTION).append('\n');
        }
        prompt.append("上次输出解析失败，请仅返回合法 JSON。");
        // 添加错误进提示词
        if (includeLastErrorInRetryPrompt && lastError != null && lastError.getMessage() != null) {
            prompt.append("\n上次失败原因：")
                .append(sanitizeErrorMessage(lastError.getMessage()));
        }
        return prompt.toString();
    }

    // 清洗错误信息
    private String sanitizeErrorMessage(String message) {
        String oneLine = message.replace('\n', ' ').replace('\r', ' ').trim();
        if (oneLine.length() > errorMessageMaxLength) {
            return oneLine.substring(0, errorMessageMaxLength) + "...";
        }
        return oneLine;
    }

    // 记录尝试指标
    private void recordAttempt(String contextTag, String status) {
        if (!isMetricsAvailable()) {
            return;
        }
        meterRegistry.counter(
            METRIC_ATTEMPTS,
            Tags.of("context", contextTag, "status", status)
        ).increment();
    }

    // 记录完整调用指标
    private void recordInvocation(String contextTag, String status, long startNanos) {
        if (!isMetricsAvailable()) {
            return;
        }
        Tags tags = Tags.of("context", contextTag, "status", status);
        meterRegistry.counter(METRIC_INVOCATIONS, tags).increment();
        meterRegistry.timer(METRIC_LATENCY, tags)
            .record(System.nanoTime() - startNanos, java.util.concurrent.TimeUnit.NANOSECONDS);
    }

    // 检查指标收集是否可用
    private boolean isMetricsAvailable() {
        return metricsEnabled && meterRegistry != null;
    }

    // 规范化上下文标签
    private String normalizeContextTag(String raw) {
        String source = (raw == null || raw.isBlank()) ? "unknown" : raw;
        String normalized = source.toLowerCase(Locale.ROOT).trim().replace(' ', '_');
        normalized = NON_ALNUM_PATTERN.matcher(normalized).replaceAll("_");
        normalized = MULTI_UNDERSCORE.matcher(normalized).replaceAll("_");
        normalized = normalized.replaceAll("^_+|_+$", "");
        if (normalized.isBlank()) {
            normalized = "unknown";
        }
        if (normalized.length() > MAX_CONTEXT_TAG_LENGTH) {
            normalized = normalized.substring(0, MAX_CONTEXT_TAG_LENGTH);
        }
        return normalized;
    }
}
