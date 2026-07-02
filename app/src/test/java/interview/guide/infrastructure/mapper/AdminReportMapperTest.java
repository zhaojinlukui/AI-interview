package interview.guide.infrastructure.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import interview.guide.common.model.AsyncTaskStatus;
import interview.guide.modules.admin.model.AdminInterviewItemDTO;
import interview.guide.modules.admin.model.AdminVoiceInterviewDetailDTO;
import interview.guide.modules.interview.model.InterviewSessionEntity;
import interview.guide.modules.voiceinterview.dto.VoiceEvaluationDetailDTO;
import interview.guide.modules.voiceinterview.model.VoiceInterviewEvaluationEntity;
import interview.guide.modules.voiceinterview.model.VoiceInterviewSessionEntity;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

@DisplayName("管理员报表映射器")
class AdminReportMapperTest {

  private final AdminReportMapper mapper = Mappers.getMapper(AdminReportMapper.class);

  @Test
  @DisplayName("应映射文本面试列表项")
  void shouldMapTextInterviewItem() {
    InterviewSessionEntity session = new InterviewSessionEntity();
    session.setId(1L);
    session.setSessionId("text-session");
    session.setSkillId("java-backend");
    session.setDifficulty("mid");
    session.setTotalQuestions(5);
    session.setOverallScore(84);
    session.setStatus(InterviewSessionEntity.SessionStatus.COMPLETED);
    session.setEvaluateStatus(AsyncTaskStatus.COMPLETED);
    session.setEvaluateError(null);
    session.setCreatedAt(LocalDateTime.of(2026, 5, 1, 9, 0));
    session.setCompletedAt(LocalDateTime.of(2026, 5, 1, 10, 0));

    AdminInterviewItemDTO dto = mapper.toTextInterviewItem(session);

    assertThat(dto.type()).isEqualTo("TEXT");
    assertThat(dto.id()).isEqualTo(1L);
    assertThat(dto.sessionId()).isEqualTo("text-session");
    assertThat(dto.totalQuestions()).isEqualTo(5);
    assertThat(dto.overallScore()).isEqualTo(84);
    assertThat(dto.status()).isEqualTo("COMPLETED");
    assertThat(dto.evaluateStatus()).isEqualTo("COMPLETED");
    assertThat(dto.completedAt()).isEqualTo(LocalDateTime.of(2026, 5, 1, 10, 0));
  }

  @Test
  @DisplayName("应映射语音面试列表项并兼容空评估字段")
  void shouldMapVoiceInterviewItemWithNullEvaluationFields() {
    VoiceInterviewSessionEntity session = new VoiceInterviewSessionEntity();
    session.setId(8L);
    session.setRoleType("backend");
    session.setSkillId("java-backend");
    session.setDifficulty("senior");
    session.setStatus(null);
    session.setEvaluateStatus(null);
    session.setEvaluateError("queued");
    session.setCreatedAt(LocalDateTime.of(2026, 6, 1, 9, 0));
    session.setEndTime(LocalDateTime.of(2026, 6, 1, 9, 30));
    session.setUpdatedAt(LocalDateTime.of(2026, 6, 1, 9, 35));

    VoiceInterviewEvaluationEntity evaluation = new VoiceInterviewEvaluationEntity();
    evaluation.setSessionId(8L);
    evaluation.setOverallScore(92);

    AdminInterviewItemDTO scored = mapper.toVoiceInterviewItem(session, evaluation);
    AdminInterviewItemDTO pending = mapper.toVoiceInterviewItem(session, null);

    assertThat(scored.type()).isEqualTo("VOICE");
    assertThat(scored.id()).isEqualTo(8L);
    assertThat(scored.sessionId()).isEqualTo("8");
    assertThat(scored.totalQuestions()).isNull();
    assertThat(scored.overallScore()).isEqualTo(92);
    assertThat(scored.status()).isNull();
    assertThat(scored.evaluateStatus()).isNull();
    assertThat(scored.completedAt()).isEqualTo(LocalDateTime.of(2026, 6, 1, 9, 30));
    assertThat(pending.overallScore()).isNull();
  }

  @Test
  @DisplayName("应映射允许空状态的语音面试详情")
  void shouldMapVoiceInterviewDetailWithNullableStatuses() {
    VoiceInterviewSessionEntity session = new VoiceInterviewSessionEntity();
    session.setId(9L);
    session.setRoleType("backend");
    session.setSkillId("java-backend");
    session.setDifficulty("junior");
    session.setStatus(null);
    session.setEvaluateStatus(null);
    session.setEvaluateError(null);
    session.setStartTime(LocalDateTime.of(2026, 6, 2, 9, 0));
    session.setEndTime(LocalDateTime.of(2026, 6, 2, 9, 20));
    session.setUpdatedAt(LocalDateTime.of(2026, 6, 2, 9, 25));
    VoiceEvaluationDetailDTO evaluation = VoiceEvaluationDetailDTO.builder()
        .sessionId(9L)
        .overallScore(87)
        .build();

    AdminVoiceInterviewDetailDTO dto = mapper.toVoiceInterviewDetail(session, evaluation);

    assertThat(dto.sessionId()).isEqualTo(9L);
    assertThat(dto.roleType()).isEqualTo("backend");
    assertThat(dto.status()).isNull();
    assertThat(dto.evaluateStatus()).isNull();
    assertThat(dto.evaluation()).isSameAs(evaluation);
  }
}
