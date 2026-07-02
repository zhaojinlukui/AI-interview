package interview.guide.modules.interview.model;

import java.util.List;

/**
 * 面试会话 DTO.
 */
public record InterviewSessionDTO(
        String sessionId,                     // 面试会话业务 ID
        String resumeText,                    // 简历文本
        int totalQuestions,                   // 总题数
        int currentQuestionIndex,             // 当前题目索引
        List<InterviewQuestionDTO> questions, // 题目列表
        SessionStatus status                  // 会话状态
) {
    public enum SessionStatus {
        CREATED,
        IN_PROGRESS,
        COMPLETED,
        EVALUATED
    }
}
