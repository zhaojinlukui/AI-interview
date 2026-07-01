package interview.guide.modules.admin.model;

import interview.guide.modules.voiceinterview.dto.VoiceEvaluationDetailDTO;
import java.time.LocalDateTime;

public record AdminVoiceInterviewDetailDTO(
    Long sessionId,
    String roleType,
    String skillId,
    String difficulty,
    String status,
    String evaluateStatus,
    String evaluateError,
    LocalDateTime startTime,
    LocalDateTime endTime,
    LocalDateTime updatedAt,
    VoiceEvaluationDetailDTO evaluation
) {
}
