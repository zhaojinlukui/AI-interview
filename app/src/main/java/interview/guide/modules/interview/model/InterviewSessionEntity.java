package interview.guide.modules.interview.model;

import interview.guide.common.model.AsyncTaskStatus;
import interview.guide.modules.resume.model.ResumeEntity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

@Entity
@Table(
        name = "interview_sessions",
        indexes = {
                @Index(name = "idx_interview_session_resume_created", columnList = "resume_id,created_at"),
                @Index(
                        name = "idx_interview_session_resume_status_created",
                        columnList = "resume_id,status,created_at"),
                @Index(name = "idx_interview_session_skill_created", columnList = "skillId,createdAt"),
                @Index(name = "idx_interview_session_user_created", columnList = "userId,createdAt")
        })
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InterviewSessionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;                                 // 主键 ID

    @Column(nullable = false, unique = true, length = 36)
    private String sessionId;                        // 面试会话业务 ID

    @Column(length = 64)
    private String userId;                           // 所属用户 ID

    @Column(length = 64)
    @Builder.Default
    private String skillId = "java-backend";         // 面试技能 ID

    @Column(length = 16)
    @Builder.Default
    private String difficulty = "mid";               // 面试难度

    @Enumerated(EnumType.STRING)
    @Column(length = 32)
    @Builder.Default
    private PracticeType practiceType = PracticeType.NORMAL; // 练习类型

    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private MistakeSourceType sourceType;            // 错题来源类型

    @Column(length = 64)
    private String sourceSessionId;                  // 错题来源场次 ID

    @Column(name = "resume_id", insertable = false, updatable = false)
    private Long resumeId;                           // 关联简历 ID

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "resume_id")
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private ResumeEntity resume;                     // 关联简历

    private Integer totalQuestions;                  // 总题数

    @Builder.Default
    private Integer currentQuestionIndex = 0;        // 当前题目索引

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    @Builder.Default
    private SessionStatus status = SessionStatus.CREATED; // 会话状态

    @Column(columnDefinition = "TEXT")
    private String questionsJson;                    // 题目列表 JSON

    private Integer overallScore;                    // 总评分

    @Column(columnDefinition = "TEXT")
    private String overallFeedback;                  // 总体反馈

    @Column(columnDefinition = "TEXT")
    private String strengthsJson;                    // 优势列表 JSON

    @Column(columnDefinition = "TEXT")
    private String improvementsJson;                 // 改进建议 JSON

    @Column(columnDefinition = "TEXT")
    private String referenceAnswersJson;             // 参考答案 JSON

    @OneToMany(mappedBy = "session", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private List<InterviewAnswerEntity> answers = new ArrayList<>(); // 面试答案列表

    @Column(nullable = false)
    private LocalDateTime createdAt;                 // 创建时间

    private LocalDateTime completedAt;               // 完成时间

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private AsyncTaskStatus evaluateStatus;          // 异步评估状态

    @Column(length = 500)
    private String evaluateError;                    // 异步评估失败信息

    public enum SessionStatus {
        CREATED,
        IN_PROGRESS,
        COMPLETED,
        EVALUATED
    }

    public enum PracticeType {
        NORMAL,
        MISTAKE_REVIEW
    }

    public enum MistakeSourceType {
        TEXT,
        VOICE
    }

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }

    public void addAnswer(InterviewAnswerEntity answer) {
        answers.add(answer);
        answer.setSession(this);
    }
}
