package interview.guide.modules.resume.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * 简历评测结果实体
 */
@Entity
@Table(name = "resume_analyses")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ResumeAnalysisEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;                  // 主键 ID

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "resume_id", nullable = false)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private ResumeEntity resume;      // 关联简历

    private Integer overallScore;     // 总分（0-100）

    private Integer contentScore;     // 内容完整性评分（0-25）

    private Integer structureScore;   // 结构清晰度评分（0-20）

    private Integer skillMatchScore;  // 技能匹配度评分（0-25）

    private Integer expressionScore;  // 表达专业性评分（0-15）

    private Integer projectScore;     // 项目经验评分（0-15）

    @Column(columnDefinition = "TEXT")
    private String summary;           // 简历摘要

    @Column(columnDefinition = "TEXT")
    private String strengthsJson;     // 优点列表 JSON

    @Column(columnDefinition = "TEXT")
    private String suggestionsJson;   // 改进建议列表 JSON

    @Column(nullable = false)
    private LocalDateTime analyzedAt; // 评测时间

    @PrePersist
    protected void onCreate() {
        analyzedAt = LocalDateTime.now();
    }
}
