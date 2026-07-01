package interview.guide.modules.voiceinterview.dto;

import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 语音面试会话响应 DTO.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SessionResponseDTO {

  private Long sessionId;          // 会话 ID
  private String roleType;         // 面试角色类型
  private String currentPhase;     // 当前面试阶段
  private String status;           // 会话状态
  private LocalDateTime startTime; // 开始时间
  private Integer plannedDuration; // 计划时长（分钟）
  private String webSocketUrl;     // WebSocket 连接地址
}
