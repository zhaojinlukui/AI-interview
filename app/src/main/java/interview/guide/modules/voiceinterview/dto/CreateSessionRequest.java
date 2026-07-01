package interview.guide.modules.voiceinterview.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateSessionRequest {

  private String roleType;               // 面试角色类型
  private String skillId;                // 面试技能 ID
  private String difficulty;             // 面试难度
  private String customJdText;           // 自定义岗位描述
  private Long resumeId;                 // 关联简历 ID

  @Builder.Default
  private Boolean introEnabled = false;  // 是否启用开场阶段

  @Builder.Default
  private Boolean techEnabled = true;    // 是否启用技术阶段

  @Builder.Default
  private Boolean projectEnabled = true; // 是否启用项目阶段

  @Builder.Default
  private Boolean hrEnabled = true;      // 是否启用 HR 阶段

  @Builder.Default
  private Integer plannedDuration = 30;  // 计划时长（分钟）
}
