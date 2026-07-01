package interview.guide.modules.interview.model;

/**
 * 提交答案响应.
 */
public record SubmitAnswerResponse(
    boolean hasNextQuestion,           // 是否还有下一题
    InterviewQuestionDTO nextQuestion, // 下一题信息
    int currentIndex,                  // 当前题目索引
    int totalQuestions                 // 总题数
) {
}
