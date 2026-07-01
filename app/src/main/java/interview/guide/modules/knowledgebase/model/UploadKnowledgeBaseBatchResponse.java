package interview.guide.modules.knowledgebase.model;

import java.util.List;

/**
 * 批量导入知识库响应.
 */
public record UploadKnowledgeBaseBatchResponse(
  List<UploadKnowledgeBaseBatchItemResponse> items,
  int totalCount,
  int successCount,
  int failureCount,
  int duplicateCount
) {
}
