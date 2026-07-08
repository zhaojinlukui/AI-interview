package interview.guide.modules.interview.model;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 面试详情 DTO.
 */
public record InterviewDetailDTO(
        Long id,                       // 主键 ID
        String sessionId,              // 面试会话业务 ID
        Integer totalQuestions,        // 总题数
        String status,                 // 会话状态
        String evaluateStatus,         // 异步评估状态
        String evaluateError,          // 异步评估失败信息
        Integer overallScore,          // 总评分
        String overallFeedback,        // 总体反馈
        LocalDateTime createdAt,       // 创建时间
        LocalDateTime completedAt,     // 完成时间
        List<Object> questions,        // 题目列表
        List<String> strengths,        // 优势列表
        List<String> improvements,     // 改进建议
        List<Object> referenceAnswers, // 参考答案列表
        List<AnswerDetailDTO> answers  // 答案详情列表
) {
    /**
     * 答案详情 DTO.
     */
    public record AnswerDetailDTO(
            Integer questionIndex,   // 问题索引
            String question,         // 问题内容
            String category,         // 问题类别
            String userAnswer,       // 用户答案
            Integer score,           // 得分
            String feedback,         // 评估反馈
            String referenceAnswer,  // 参考答案
            List<String> keyPoints,  // 关键点列表
            LocalDateTime answeredAt // 回答时间
    ) {
    }
}
