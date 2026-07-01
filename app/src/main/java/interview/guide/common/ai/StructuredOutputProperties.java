package interview.guide.common.ai;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

// 结构化输出配置属性类
@Data
@Component
@ConfigurationProperties(prefix = "app.ai")
public class StructuredOutputProperties {

    private int structuredMaxAttempts = 2;                              // 最大尝试次数（包括首次）
    private boolean structuredIncludeLastError = true;                  // 重试时是否把上次的解析错误信息附加到提示词中
    private boolean structuredRetryUseRepairPrompt = true;              // 重试时是否使用增强的修复提示词
    private boolean structuredRetryAppendStrictJsonInstruction = true;  // 重试时是否追加严格JSON格式指令
    private int structuredErrorMessageMaxLength = 200;                  // 附加到重试提示词中的错误信息最大长度
    private boolean structuredMetricsEnabled = true;                    // 是否启用 Micrometer 指标收集
}
