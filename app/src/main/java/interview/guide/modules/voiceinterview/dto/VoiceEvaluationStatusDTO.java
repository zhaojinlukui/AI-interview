package interview.guide.modules.voiceinterview.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 语音面试评估状态响应 DTO.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VoiceEvaluationStatusDTO {

  private String evaluateStatus;               // 评估任务状态（PENDING/PROCESSING/COMPLETED/FAILED）
  private String evaluateError;                // 评估失败信息
  private VoiceEvaluationDetailDTO evaluation; // 完整评估结果
}
