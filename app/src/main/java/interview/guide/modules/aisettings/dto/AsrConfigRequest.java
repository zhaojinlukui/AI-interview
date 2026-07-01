package interview.guide.modules.aisettings.dto;

public record AsrConfigRequest(
    String url,                            // ASR 服务地址
    String model,                          // ASR 模型名称
    String apiKey,                         // API Key
    String language,                       // 识别语言
    String format,                         // 音频格式
    Integer sampleRate,                    // 采样率
    Boolean enableTurnDetection,           // 是否启用端点检测
    String turnDetectionType,              // 端点检测类型
    Float turnDetectionThreshold,          // 端点检测阈值
    Integer turnDetectionSilenceDurationMs // 静音判定时长（毫秒）
) {
}
