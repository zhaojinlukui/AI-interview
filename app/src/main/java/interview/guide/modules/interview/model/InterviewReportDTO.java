package interview.guide.modules.interview.model;

import java.util.List;

/**
 * 面试评估报告.
 */
public record InterviewReportDTO(
    String sessionId,                         // 面试会话业务 ID
    int totalQuestions,                       // 总题数
    int overallScore,                         // 总分（0-100）
    List<QuestionEvaluation> questionDetails, // 每题评估详情
    String overallFeedback,                   // 总体评价
    List<String> strengths,                   // 优势列表
    List<String> improvements,                // 改进建议
    List<ReferenceAnswer> referenceAnswers    // 参考答案列表
) {
  /**
   * 问题评估详情.
   */
  public record QuestionEvaluation(
      int questionIndex,      // 问题索引
      String question,        // 问题内容
      String category,        // 问题类别
      String userAnswer,      // 用户答案
      int score,              // 得分
      String feedback         // 评估反馈
  ) {
  }

  /**
   * 参考答案.
   */
  public record ReferenceAnswer(
      int questionIndex,      // 问题索引
      String question,        // 问题内容
      String referenceAnswer, // 参考答案
      List<String> keyPoints  // 关键点列表
  ) {
  }
}
