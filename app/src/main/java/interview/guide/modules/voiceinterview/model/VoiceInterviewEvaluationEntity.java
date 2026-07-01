package interview.guide.modules.voiceinterview.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 语音面试评估实体.
 */
@Entity
@Table(name = "voice_interview_evaluations")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VoiceInterviewEvaluationEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;                        // 主键 ID

  @Column(name = "session_id", unique = true)
  private Long sessionId;                 // 语音面试会话 ID

  @Column(name = "overall_score")
  private Integer overallScore;           // 总评分

  @Column(name = "overall_feedback", columnDefinition = "TEXT")
  private String overallFeedback;         // 总体反馈

  @Column(name = "question_evaluations_json", columnDefinition = "TEXT")
  private String questionEvaluationsJson; // 单题评估 JSON

  @Column(name = "strengths_json", columnDefinition = "TEXT")
  private String strengthsJson;           // 优势列表 JSON

  @Column(name = "improvements_json", columnDefinition = "TEXT")
  private String improvementsJson;        // 改进建议 JSON

  @Column(name = "reference_answers_json", columnDefinition = "TEXT")
  private String referenceAnswersJson;    // 参考答案 JSON

  @Column(name = "interviewer_role")
  private String interviewerRole;         // 面试官角色

  @Column(name = "interview_date")
  private LocalDateTime interviewDate;    // 面试日期

  @Column(name = "created_at")
  private LocalDateTime createdAt;        // 创建时间

  @PrePersist
  protected void onCreate() {
    this.createdAt = LocalDateTime.now();
  }
}
