package interview.guide.modules.aisettings.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "system_ai_settings")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SystemAiSettingsEntity {

  public static final Long SINGLETON_ID = 1L;

  @Id
  private Long id;

  @Column(nullable = false)
  private Integer resumeProjectWeight;

  @Column(nullable = false)
  private Integer resumeSkillMatchWeight;

  @Column(nullable = false)
  private Integer resumeContentWeight;

  @Column(nullable = false)
  private Integer resumeStructureWeight;

  @Column(nullable = false)
  private Integer resumeExpressionWeight;

  @Column(nullable = false)
  private Integer interviewResumeQuestionRatio;

  @Column(nullable = false)
  private Integer interviewDirectionQuestionRatio;

  @Column(nullable = false)
  private Double interviewQuestionTemperature;

  @Column(nullable = false)
  private Double interviewFollowUpTemperature;

  @Column(nullable = false)
  private Double interviewScoringTemperature;

  @Column(nullable = false)
  private Double interviewCommentTemperature;

  @Column(nullable = false)
  private Integer ragTopkShort;

  @Column(nullable = false)
  private Integer ragTopkMedium;

  @Column(nullable = false)
  private Integer ragTopkLong;

  @Column(nullable = false)
  private Double ragMinScoreShort;

  @Column(nullable = false)
  private Double ragMinScoreMedium;

  @Column(nullable = false)
  private Double ragMinScoreLong;

  @Column(nullable = false, updatable = false)
  private LocalDateTime createdAt;

  private LocalDateTime updatedAt;

  @PrePersist
  protected void onCreate() {
    createdAt = LocalDateTime.now();
    updatedAt = LocalDateTime.now();
  }

  @PreUpdate
  protected void onUpdate() {
    updatedAt = LocalDateTime.now();
  }
}
