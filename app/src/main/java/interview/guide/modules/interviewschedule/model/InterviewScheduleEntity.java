package interview.guide.modules.interviewschedule.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(
    name = "interview_schedule",
    indexes = {
        @Index(name = "idx_schedule_user_time", columnList = "userId,interview_time"),
        @Index(name = "idx_schedule_user_status", columnList = "userId,status")
    })
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InterviewScheduleEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;                                 // 主键 ID

  @Column(name = "company_name", nullable = false)
  private String companyName;                      // 公司名称

  @Column(length = 64)
  private String userId;                           // 所属用户 ID

  @Column(nullable = false)
  private String position;                         // 面试岗位

  @Column(name = "interview_time", nullable = false)
  private LocalDateTime interviewTime;             // 面试时间

  @Column(name = "interview_type")
  private String interviewType;                    // 面试形式（ONSITE/VIDEO/PHONE）

  @Column(name = "meeting_link", columnDefinition = "TEXT")
  private String meetingLink;                      // 会议链接

  @Column(name = "round_number")
  @Builder.Default
  private Integer roundNumber = 1;                 // 面试轮次

  private String interviewer;                      // 面试官

  @Column(columnDefinition = "TEXT")
  private String notes;                            // 备注

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  @Builder.Default
  private InterviewStatus status = InterviewStatus.PENDING; // 日程状态

  @Column(name = "created_at", updatable = false)
  private LocalDateTime createdAt;                 // 创建时间

  @Column(name = "updated_at")
  private LocalDateTime updatedAt;                 // 更新时间

  @PrePersist
  protected void onCreate() {
    createdAt = LocalDateTime.now();
    updatedAt = LocalDateTime.now();
  }

  @PreUpdate
  protected void onUpdate() {
    updatedAt = LocalDateTime.now();
  }
}
