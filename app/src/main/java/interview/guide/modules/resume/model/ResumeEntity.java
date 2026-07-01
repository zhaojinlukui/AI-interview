package interview.guide.modules.resume.model;

import interview.guide.common.model.AsyncTaskStatus;
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
 * 简历实体
 */
@Entity
@Table(
        name = "resumes",
        indexes = {
                @Index(name = "idx_resume_user_uploaded", columnList = "userId,uploadedAt"),
                @Index(name = "idx_resume_user_hash", columnList = "userId,fileHash", unique = true)
        })
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ResumeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;                                 // 主键 ID

    @Column(nullable = false, length = 64)
    private String fileHash;                         // 文件 SHA-256 哈希值

    @Column(length = 64)
    private String userId;                           // 所属用户 ID

    @Column(nullable = false)
    private String originalFilename;                 // 原始文件名

    private Long fileSize;                           // 文件大小（字节）

    private String contentType;                      // 文件 MIME 类型

    @Column(length = 500)
    private String storageKey;                       // RustFS 存储 Key

    @Column(length = 1000)
    private String storageUrl;                       // RustFS 访问 URL

    @Column(columnDefinition = "TEXT")
    private String resumeText;                       // 解析后的简历文本

    @Column(nullable = false)
    private LocalDateTime uploadedAt;                // 上传时间

    private LocalDateTime lastAccessedAt;            // 最后访问时间

    @Builder.Default
    private Integer accessCount = 0;                 // 访问次数

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    @Builder.Default
    private AsyncTaskStatus analyzeStatus = AsyncTaskStatus.PENDING; // 简历分析状态

    @Column(length = 500)
    private String analyzeError;                     // 简历分析失败信息

    @PrePersist
    protected void onCreate() {
        uploadedAt = LocalDateTime.now();
        lastAccessedAt = LocalDateTime.now();
        accessCount = 1;
    }

    public void incrementAccessCount() {
        this.accessCount++;
        this.lastAccessedAt = LocalDateTime.now();
    }
}
