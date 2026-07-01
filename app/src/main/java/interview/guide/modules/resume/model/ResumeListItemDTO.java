package interview.guide.modules.resume.model;

import interview.guide.common.model.AsyncTaskStatus;
import java.time.LocalDateTime;

/**
 * 简历列表项 DTO
 */
public record ResumeListItemDTO(
    Long id,                       // 主键 ID
    String filename,               // 文件名
    Long fileSize,                 // 文件大小（字节）
    LocalDateTime uploadedAt,      // 上传时间
    Integer accessCount,           // 访问次数
    Integer latestScore,           // 最新分析评分
    LocalDateTime lastAnalyzedAt,  // 最后分析时间
    Integer interviewCount,        // 关联面试数量
    AsyncTaskStatus analyzeStatus, // 简历分析状态
    String analyzeError            // 简历分析失败信息
) {
}
