package interview.guide.modules.user.model;

import java.time.LocalDate;
import java.util.List;

/**
 * 个人中心统计数据响应
 */
public record ProfileStatsResponse(
    PeriodMetricDTO last7Days,
    PeriodMetricDTO last30Days,
    List<GrowthTrendPointDTO> growthTrend,
    List<WeaknessTrendDTO> weakItems
) {
  /**
   * 指定周期内的面试数量和平均分指标
   */
  public record PeriodMetricDTO(
      int days,
      long interviewCount,
      long previousInterviewCount,
      long interviewCountChange,
      Double averageScore,
      Double previousAverageScore,
      Double averageScoreChange
  ) {
  }

  /**
   * 每日成长趋势数据点
   */
  public record GrowthTrendPointDTO(
      LocalDate date,
      long interviewCount,
      Double averageScore
  ) {
  }

  /**
   * 薄弱项趋势数据
   */
  public record WeaknessTrendDTO(
      String item,
      Double averageScore,
      Double previousAverageScore,
      Double averageScoreChange,
      long sampleCount
  ) {
  }
}
