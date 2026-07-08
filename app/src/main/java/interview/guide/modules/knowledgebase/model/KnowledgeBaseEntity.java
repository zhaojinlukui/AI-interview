package interview.guide.modules.knowledgebase.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 知识库实体.
 */
@Entity
@Table(
        name = "knowledge_bases",
        indexes = {
                @Index(name = "idx_kb_user_uploaded", columnList = "userId,uploadedAt"),
                @Index(name = "idx_kb_user_hash", columnList = "userId,fileHash", unique = true)
        })
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeBaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;                                 // 主键 ID

    @Column(nullable = false, length = 64)
    private String fileHash;                         // 文件 SHA-256 哈希值

    @Column(length = 64)
    private String userId;                           // 所属用户 ID

    @Column(nullable = false)
    private String name;                             // 知识库名称

    @Column(nullable = false)
    private String originalFilename;                 // 原始文件名

    private Long fileSize;                           // 文件大小（字节）

    private String contentType;                      // 文件 MIME 类型

    @Column(length = 500)
    private String storageKey;                       // RustFS 存储 Key

    @Column(length = 1000)
    private String storageUrl;                       // RustFS 访问 URL

    @Column(nullable = false)
    private LocalDateTime uploadedAt;                // 上传时间

    private LocalDateTime lastAccessedAt;            // 最后访问时间

    @Builder.Default
    private Integer accessCount = 0;                 // 访问次数

    @Builder.Default
    private Integer questionCount = 0;               // 提问次数

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    @Builder.Default
    private VectorStatus vectorStatus = VectorStatus.PENDING; // 向量化状态

    @Column(length = 500)
    private String vectorError;                      // 向量化失败信息

    @Builder.Default
    private Integer chunkCount = 0;                  // 向量分块数量

    @PrePersist
    protected void onCreate() {
        uploadedAt = LocalDateTime.now();
        lastAccessedAt = LocalDateTime.now();
    }

    public void updateAccessedAt() {
        this.lastAccessedAt = LocalDateTime.now();
    }

}
