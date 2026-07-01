package interview.guide.modules.resume.model;

import interview.guide.common.model.AsyncTaskStatus;
import interview.guide.modules.interview.model.InterviewHistoryItemDTO;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 简历详情 DTO
 */
public record ResumeDetailDTO(
    Long id,                                 // 主键 ID
    String filename,                         // 文件名
    Long fileSize,                           // 文件大小（字节）
    String contentType,                      // 文件 MIME 类型
    String storageUrl,                       // 文件访问 URL
    LocalDateTime uploadedAt,                // 上传时间
    Integer accessCount,                     // 访问次数
    String resumeText,                       // 简历文本
    AsyncTaskStatus analyzeStatus,           // 简历分析状态
    String analyzeError,                     // 简历分析失败信息
    List<AnalysisHistoryDTO> analyses,       // 分析历史列表
    List<InterviewHistoryItemDTO> interviews // 关联面试历史列表
) {
    /**
     * 分析历史 DTO
     */
    public record AnalysisHistoryDTO(
        Long id,                  // 分析记录 ID
        Integer overallScore,     // 总评分
        Integer contentScore,     // 内容完整性评分
        Integer structureScore,   // 结构清晰度评分
        Integer skillMatchScore,  // 技能匹配度评分
        Integer expressionScore,  // 表达专业性评分
        Integer projectScore,     // 项目经验评分
        String summary,           // 简历摘要
        LocalDateTime analyzedAt, // 分析时间
        List<String> strengths,   // 优点列表
        List<Object> suggestions  // 改进建议列表
    ) {
    }
}
