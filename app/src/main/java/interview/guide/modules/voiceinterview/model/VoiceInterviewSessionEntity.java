package interview.guide.modules.voiceinterview.model;

import interview.guide.common.model.AsyncTaskStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "voice_interview_sessions")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VoiceInterviewSessionEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;                                 // 主键 ID

  @Column(name = "user_id")
  private String userId;                           // 用户 ID

  @Column(name = "role_type", nullable = false)
  private String roleType;                         // 面试角色类型

  @Column(name = "skill_id", length = 64)
  @Builder.Default
  private String skillId = "java-backend";         // 面试技能 ID

  @Column(name = "difficulty", length = 16)
  @Builder.Default
  private String difficulty = "mid";               // 面试难度

  @Column(name = "custom_jd_text", columnDefinition = "TEXT")
  private String customJdText;                     // 自定义岗位描述

  @Column(name = "resume_id")
  private Long resumeId;                           // 关联简历 ID

  @Column(name = "intro_enabled")
  @Builder.Default
  private Boolean introEnabled = true;             // 是否启用开场阶段

  @Column(name = "tech_enabled")
  @Builder.Default
  private Boolean techEnabled = true;              // 是否启用技术阶段

  @Column(name = "project_enabled")
  @Builder.Default
  private Boolean projectEnabled = true;           // 是否启用项目阶段

  @Column(name = "hr_enabled")
  @Builder.Default
  private Boolean hrEnabled = true;                // 是否启用 HR 阶段

  @Column(name = "current_phase")
  @Enumerated(EnumType.STRING)
  private InterviewPhase currentPhase;             // 当前面试阶段

  @Column(name = "status")
  @Enumerated(EnumType.STRING)
  @Builder.Default
  private VoiceInterviewSessionStatus status = VoiceInterviewSessionStatus.IN_PROGRESS; // 会话状态

  @Column(name = "planned_duration")
  @Builder.Default
  private Integer plannedDuration = 30;            // 计划时长（分钟）

  @Column(name = "actual_duration")
  private Integer actualDuration;                  // 实际时长（分钟）

  @Column(name = "start_time")
  private LocalDateTime startTime;                 // 开始时间

  @Column(name = "end_time")
  private LocalDateTime endTime;                   // 结束时间

  @Column(name = "created_at")
  private LocalDateTime createdAt;                 // 创建时间

  @Column(name = "updated_at")
  private LocalDateTime updatedAt;                 // 更新时间

  @Column(name = "paused_at")
  private LocalDateTime pausedAt;                  // 暂停时间

  @Column(name = "resumed_at")
  private LocalDateTime resumedAt;                 // 恢复时间

  @Column(name = "evaluate_status")
  @Enumerated(EnumType.STRING)
  private AsyncTaskStatus evaluateStatus;          // 异步评估状态

  @Column(name = "evaluate_error", length = 500)
  private String evaluateError;                    // 异步评估失败信息

  @PreUpdate
  protected void onUpdate() {
    updatedAt = LocalDateTime.now();
  }

  @PrePersist
  protected void onCreate() {
    createdAt = LocalDateTime.now();
    updatedAt = LocalDateTime.now();
    startTime = LocalDateTime.now();
  }

  public enum InterviewPhase {
    INTRO,
    TECH,
    PROJECT,
    HR,
    COMPLETED
  }
}
