package interview.guide.modules.interview.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * 面试答案实体.
 */
@Entity
@Table(
    name = "interview_answers",
    uniqueConstraints = {
        @UniqueConstraint(
            name = "uk_interview_answer_session_question",
            columnNames = {"session_id", "question_index"})
    },
    indexes = {
        @Index(
            name = "idx_interview_answer_session_question",
            columnList = "session_id,question_index")
    })
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InterviewAnswerEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;                        // 主键 ID

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "session_id", nullable = false)
  @ToString.Exclude
  @EqualsAndHashCode.Exclude
  private InterviewSessionEntity session; // 关联面试会话

  @Column(name = "question_index")
  private Integer questionIndex;          // 问题索引

  @Column(columnDefinition = "TEXT")
  private String question;                // 问题内容

  private String category;                // 问题类别

  @Column(columnDefinition = "TEXT")
  private String userAnswer;              // 用户答案

  private Integer score;                  // 得分（0-100）

  @Column(columnDefinition = "TEXT")
  private String feedback;                // 评估反馈

  @Column(columnDefinition = "TEXT")
  private String referenceAnswer;         // 参考答案

  @Column(columnDefinition = "TEXT")
  private String keyPointsJson;           // 关键点 JSON

  @Column(nullable = false)
  private LocalDateTime answeredAt;       // 回答时间

  @PrePersist
  protected void onCreate() {
    answeredAt = LocalDateTime.now();
  }
}
