package interview.guide.modules.voiceinterview.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WebSocketSubtitleMessage {

  private String type;     // 消息类型（subtitle）
  private String text;     // 字幕文本
  private Boolean isFinal; // 是否最终识别结果
}
