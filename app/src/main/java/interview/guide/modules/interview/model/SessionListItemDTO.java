package interview.guide.modules.interview.model;

import interview.guide.common.model.AsyncTaskStatus;
import interview.guide.modules.interview.model.InterviewSessionEntity.SessionStatus;
import java.time.LocalDateTime;

/**
 * 面试会话列表项 DTO（轻量，不含题目/答案等大字段）.
 */
public record SessionListItemDTO(
        String sessionId,               // 面试会话业务 ID
        String skillId,                 // 面试技能 ID
        String difficulty,              // 面试难度
        Long resumeId,                  // 关联简历 ID
        int totalQuestions,             // 总题数
        SessionStatus status,           // 会话状态
        AsyncTaskStatus evaluateStatus, // 异步评估状态
        String evaluateError,           // 异步评估失败信息
        Integer overallScore,           // 总评分
        LocalDateTime createdAt,        // 创建时间
        LocalDateTime completedAt       // 完成时间
) {
    public static SessionListItemDTO from(InterviewSessionEntity e) {
        return new SessionListItemDTO(
                e.getSessionId(),
                e.getSkillId(),
                e.getDifficulty(),
                e.getResumeId(),
                e.getTotalQuestions() != null ? e.getTotalQuestions() : 0,
                e.getStatus(),
                e.getEvaluateStatus(),
                e.getEvaluateError(),
                e.getOverallScore(),
                e.getCreatedAt(),
                e.getCompletedAt()
        );
    }
}
