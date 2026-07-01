package interview.guide.modules.knowledgebase.model;

/**
 * 知识库统计信息 DTO.
 */
public record KnowledgeBaseStatsDTO(
    long totalCount,         // 知识库总数
    long totalQuestionCount, // 总提问次数
    long completedCount,     // 已完成向量化数量
    long processingCount     // 处理中数量
) {
}
