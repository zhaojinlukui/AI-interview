package interview.guide.modules.aisettings.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class AsrConfigDTO {

  private String url;                         // ASR 服务地址
  private String model;                       // ASR 模型名称
  private String maskedApiKey;                // 脱敏后的 API Key
  private String language;                    // 识别语言
  private String format;                      // 音频格式
  private int sampleRate;                     // 采样率
  private boolean enableTurnDetection;        // 是否启用端点检测
  private String turnDetectionType;           // 端点检测类型
  private float turnDetectionThreshold;       // 端点检测阈值
  private int turnDetectionSilenceDurationMs; // 静音判定时长（毫秒）
}
