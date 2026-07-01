package interview.guide.modules.interview.model;

import java.util.List;

/**
 * 简历分析响应 DTO.
 */
public record ResumeAnalysisResponse(
    int overallScore,             // 总分（0-100）
    ScoreDetail scoreDetail,      // 各维度评分
    String summary,               // 简历摘要
    List<String> strengths,       // 优点列表
    List<Suggestion> suggestions, // 改进建议列表
    String originalText           // 原始简历文本
) {
  /**
   * 各维度评分详情.
   */
  public record ScoreDetail(
      int contentScore,     // 内容完整性评分（0-25）
      int structureScore,   // 结构清晰度评分（0-20）
      int skillMatchScore,  // 技能匹配度评分（0-25）
      int expressionScore,  // 表达专业性评分（0-15）
      int projectScore      // 项目经验评分（0-15）
  ) {
  }

  /**
   * 改进建议.
   */
  public record Suggestion(
      String category,      // 建议类别
      String priority,      // 优先级
      String issue,         // 问题描述
      String recommendation // 具体建议
  ) {
  }
}
