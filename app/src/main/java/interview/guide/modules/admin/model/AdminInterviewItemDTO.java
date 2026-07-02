package interview.guide.modules.admin.model;

import java.time.LocalDateTime;

/**
 * 管理后台面试列表项DTO
 */
public record AdminInterviewItemDTO(
        String type,                                // 面试类型：VOICE/TEXT/VIDEO等
        Long id,                                    // 面试记录ID
        String sessionId,                           // 会话ID（业务编号）
        String skillId,                             // 技能ID
        String difficulty,                          // 难度等级
        Integer totalQuestions,                     // 总题数
        Integer overallScore,                       // 综合得分
        String status,                              // 面试状态：WAITING/IN_PROGRESS/COMPLETED/CANCELLED/TIMEOUT
        String evaluateStatus,                      // 评估状态：PENDING/PROCESSING/SUCCESS/FAILED
        String evaluateError,                       // 评估失败时的错误信息
        LocalDateTime createdAt,                    // 创建时间
        LocalDateTime completedAt                   // 完成时间

) {
}