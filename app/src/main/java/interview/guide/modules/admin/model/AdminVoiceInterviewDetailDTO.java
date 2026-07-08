package interview.guide.modules.admin.model;

import interview.guide.modules.voiceinterview.dto.VoiceEvaluationDetailDTO;
import java.time.LocalDateTime;

/**
 * 管理后台语音面试详情DTO
 */
public record AdminVoiceInterviewDetailDTO(
        Long sessionId,                             // 面试会话ID
        String roleType,                            // 角色类型：CANDIDATE/INTERVIEWER
        String skillId,                             // 技能ID
        String difficulty,                          // 难度等级
        String status,                              // 面试状态：WAITING/IN_PROGRESS/COMPLETED/CANCELLED/TIMEOUT
        String evaluateStatus,                      // 评估状态：PENDING/PROCESSING/SUCCESS/FAILED
        String evaluateError,                       // 评估失败时的错误信息
        LocalDateTime startTime,                    // 面试开始时间
        LocalDateTime endTime,                      // 面试结束时间
        LocalDateTime updatedAt,                    // 最后更新时间
        VoiceEvaluationDetailDTO evaluation         // 语音评估详情

) {
}