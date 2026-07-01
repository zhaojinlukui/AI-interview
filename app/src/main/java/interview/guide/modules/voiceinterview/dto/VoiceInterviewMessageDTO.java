package interview.guide.modules.voiceinterview.dto;

import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VoiceInterviewMessageDTO {

  private Long id;                   // 消息 ID
  private Long sessionId;            // 会话 ID
  private String messageType;        // 消息类型
  private String phase;              // 面试阶段
  private String userRecognizedText; // 用户语音识别文本
  private String aiGeneratedText;    // AI 生成文本
  private LocalDateTime timestamp;   // 消息时间
  private Integer sequenceNum;       // 消息序号
}
