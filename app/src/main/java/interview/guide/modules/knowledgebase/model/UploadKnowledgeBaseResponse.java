package interview.guide.modules.knowledgebase.model;

/**
 * 知识库上传响应.
 */
public record UploadKnowledgeBaseResponse(
        KnowledgeBaseInfo knowledgeBase,
        StorageInfo storage,
        boolean duplicate
) {

    public record KnowledgeBaseInfo(
            Long id,
            String name,
            Long fileSize,
            int contentLength,
            VectorStatus vectorStatus
    ) {
    }

    public record StorageInfo(
            String fileKey,
            String fileUrl
    ) {
    }
}
