package interview.guide.modules.aisettings.dto;

public record TtsConfigRequest(
    String model,        // TTS 模型名称
    String apiKey,       // API Key
    String voice,        // 音色
    String format,       // 音频格式
    Integer sampleRate,  // 采样率
    String mode,         // 合成模式
    String languageType, // 语言类型
    Float speechRate,    // 语速
    Integer volume       // 音量
) {
}
