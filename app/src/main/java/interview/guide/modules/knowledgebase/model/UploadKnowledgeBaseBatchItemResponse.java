package interview.guide.modules.knowledgebase.model;

/**
 * 批量导入知识库中单个文件的处理结果.
 */
public record UploadKnowledgeBaseBatchItemResponse(
  String filename,
  boolean success,
  UploadKnowledgeBaseResponse result,
  String errorMessage
) {

  public static UploadKnowledgeBaseBatchItemResponse success(
    String filename,
    UploadKnowledgeBaseResponse result
  ) {
    return new UploadKnowledgeBaseBatchItemResponse(filename, true, result, null);
  }

  public static UploadKnowledgeBaseBatchItemResponse failure(String filename, String errorMessage) {
    return new UploadKnowledgeBaseBatchItemResponse(filename, false, null, errorMessage);
  }
}
