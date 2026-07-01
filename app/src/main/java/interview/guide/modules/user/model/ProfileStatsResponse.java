package interview.guide.modules.user.model;

import java.time.LocalDate;
import java.util.List;

public record ProfileStatsResponse(
    PeriodMetricDTO last7Days,
    PeriodMetricDTO last30Days,
    List<GrowthTrendPointDTO> growthTrend,
    List<WeaknessTrendDTO> weakItems
) {
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

  public record GrowthTrendPointDTO(
      LocalDate date,
      long interviewCount,
      Double averageScore
  ) {
  }

  public record WeaknessTrendDTO(
      String item,
      Double averageScore,
      Double previousAverageScore,
      Double averageScoreChange,
      long sampleCount
  ) {
  }
}
