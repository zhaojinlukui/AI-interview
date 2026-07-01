package interview.guide.modules.voiceinterview.dto;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 语音面试评估详情 DTO.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VoiceEvaluationDetailDTO {

  private Long sessionId;             // 会话 ID
  private int totalQuestions;         // 总题数
  private int overallScore;           // 总评分
  private String overallFeedback;     // 总体反馈
  private List<String> strengths;     // 优势列表
  private List<String> improvements;  // 改进建议
  private List<AnswerDetail> answers; // 答案详情列表

  @Data
  @Builder
  @NoArgsConstructor
  @AllArgsConstructor
  public static class AnswerDetail {

    private int questionIndex;      // 问题索引
    private String question;        // 问题内容
    private String category;        // 问题类别
    private String userAnswer;      // 用户答案
    private int score;              // 得分
    private String feedback;        // 评估反馈
    private String referenceAnswer; // 参考答案
    private List<String> keyPoints; // 关键点列表
  }
}
