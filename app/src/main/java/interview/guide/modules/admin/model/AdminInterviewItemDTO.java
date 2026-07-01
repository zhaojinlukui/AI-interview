package interview.guide.modules.admin.model;

import java.time.LocalDateTime;

public record AdminInterviewItemDTO(
    String type,
    Long id,
    String sessionId,
    String skillId,
    String difficulty,
    Integer totalQuestions,
    Integer overallScore,
    String status,
    String evaluateStatus,
    String evaluateError,
    LocalDateTime createdAt,
    LocalDateTime completedAt
) {
}
