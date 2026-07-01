package interview.guide.modules.aisettings.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class TtsConfigDTO {

  private String model;        // TTS 模型名称
  private String maskedApiKey; // 脱敏后的 API Key
  private String voice;        // 音色
  private String format;       // 音频格式
  private int sampleRate;      // 采样率
  private String mode;         // 合成模式
  private String languageType; // 语言类型
  private float speechRate;    // 语速
  private int volume;          // 音量
}
