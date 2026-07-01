package interview.guide.modules.interview.model;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 错题会话 DTO。
 */
public record MistakeSessionDTO(
    String sourceType,
    String sourceSessionId,
    String title,
    LocalDateTime createdAt,
    Integer overallScore,
    int mistakeCount,
    List<MistakeQuestionDTO> mistakes,
    String practiceType
) {
  public record MistakeQuestionDTO(
      int questionIndex,
      String question,
      String type,
      String category,
      String topicSummary,
      String userAnswer,
      Integer score,
      String feedback,
      String referenceAnswer,
      List<String> keyPoints
  ) {
  }
}
