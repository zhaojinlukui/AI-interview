package interview.guide.infrastructure.mapper;

import interview.guide.modules.admin.model.AdminInterviewItemDTO;
import interview.guide.modules.admin.model.AdminVoiceInterviewDetailDTO;
import interview.guide.modules.interview.model.InterviewSessionEntity;
import interview.guide.modules.voiceinterview.dto.VoiceEvaluationDetailDTO;
import interview.guide.modules.voiceinterview.model.VoiceInterviewEvaluationEntity;
import interview.guide.modules.voiceinterview.model.VoiceInterviewSessionEntity;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

@Mapper(
        componentModel = MappingConstants.ComponentModel.SPRING,
        unmappedTargetPolicy = ReportingPolicy.IGNORE
)
public interface AdminReportMapper {

    @Mapping(target = "type", constant = "TEXT")
    @Mapping(target = "status", expression = "java(enumName(session.getStatus()))")
    @Mapping(target = "evaluateStatus", expression = "java(enumName(session.getEvaluateStatus()))")
    AdminInterviewItemDTO toTextInterviewItem(InterviewSessionEntity session);

    @Mapping(target = "type", constant = "VOICE")
    @Mapping(target = "id", source = "session.id")
    @Mapping(target = "sessionId", expression = "java(toStringValue(session.getId()))")
    @Mapping(target = "skillId", source = "session.skillId")
    @Mapping(target = "difficulty", source = "session.difficulty")
    @Mapping(target = "totalQuestions", ignore = true)
    @Mapping(target = "overallScore", expression = "java(evaluation != null ? evaluation.getOverallScore() : null)")
    @Mapping(target = "status", expression = "java(enumName(session.getStatus()))")
    @Mapping(target = "evaluateStatus", expression = "java(enumName(session.getEvaluateStatus()))")
    @Mapping(target = "evaluateError", source = "session.evaluateError")
    @Mapping(target = "createdAt", source = "session.createdAt")
    @Mapping(target = "completedAt", source = "session.endTime")
    AdminInterviewItemDTO toVoiceInterviewItem(
            VoiceInterviewSessionEntity session,
            VoiceInterviewEvaluationEntity evaluation
    );

    @Mapping(target = "sessionId", source = "session.id")
    @Mapping(target = "status", expression = "java(enumName(session.getStatus()))")
    @Mapping(target = "evaluateStatus", expression = "java(enumName(session.getEvaluateStatus()))")
    @Mapping(target = "evaluation", source = "evaluation")
    AdminVoiceInterviewDetailDTO toVoiceInterviewDetail(
            VoiceInterviewSessionEntity session,
            VoiceEvaluationDetailDTO evaluation
    );

    default String enumName(Enum<?> value) {
        return value != null ? value.name() : null;
    }

    default String toStringValue(Long value) {
        return value != null ? value.toString() : null;
    }
}
