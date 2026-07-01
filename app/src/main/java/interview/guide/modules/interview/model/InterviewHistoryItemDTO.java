package interview.guide.modules.interview.model;

import java.time.LocalDateTime;

/**
 * 面试历史列表项 DTO（用于简历详情页展示关联面试记录）.
 */
public record InterviewHistoryItemDTO(
    Long id,                  // 主键 ID
    String sessionId,         // 面试会话业务 ID
    Integer totalQuestions,   // 总题数
    String status,            // 会话状态
    String evaluateStatus,    // 异步评估状态
    String evaluateError,     // 异步评估失败信息
    Integer overallScore,     // 总评分
    LocalDateTime createdAt,  // 创建时间
    LocalDateTime completedAt // 完成时间
) {
}
