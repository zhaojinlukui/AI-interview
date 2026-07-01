package interview.guide.modules.interview.model;

/**
 * 历史面试题目摘要（用于出题去重）.
 */
public record HistoricalQuestion(
    String question,    // 原始题面
    String type,        // Skill category key，如 MYSQL、JAVA
    String topicSummary // 知识点摘要
) {
}
