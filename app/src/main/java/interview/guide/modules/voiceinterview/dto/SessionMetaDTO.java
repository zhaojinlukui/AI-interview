package interview.guide.modules.voiceinterview.dto;

import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 语音面试会话列表项 DTO.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SessionMetaDTO {

  private Long sessionId;          // 会话 ID
  private String roleType;         // 面试角色类型
  private String status;           // 会话状态
  private String currentPhase;     // 当前面试阶段
  private LocalDateTime createdAt; // 创建时间
  private LocalDateTime updatedAt; // 更新时间
  private Integer actualDuration;  // 实际时长（分钟）
  private Long messageCount;       // 消息数量
  private String evaluateStatus;   // 异步评估状态
  private String evaluateError;    // 异步评估失败信息
  private Integer overallScore;
}
