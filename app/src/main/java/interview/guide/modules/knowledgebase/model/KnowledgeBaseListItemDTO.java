package interview.guide.modules.knowledgebase.model;

import java.time.LocalDateTime;

/**
 * 知识库列表项 DTO.
 */
public record KnowledgeBaseListItemDTO(
    Long id,                      // 主键 ID
    String name,                  // 知识库名称
    String originalFilename,      // 原始文件名
    Long fileSize,                // 文件大小（字节）
    String contentType,           // 文件 MIME 类型
    LocalDateTime uploadedAt,     // 上传时间
    LocalDateTime lastAccessedAt, // 最后访问时间
    Integer accessCount,          // 访问次数
    Integer questionCount,        // 提问次数
    VectorStatus vectorStatus,    // 向量化状态
    String vectorError,           // 向量化失败信息
    Integer chunkCount            // 向量分块数量
) {
}
