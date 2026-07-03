package interview.guide.infrastructure.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import interview.guide.common.model.AsyncTaskStatus;
import interview.guide.modules.interview.model.InterviewHistoryItemDTO;
import interview.guide.modules.resume.model.ResumeAnalysisEntity;
import interview.guide.modules.resume.model.ResumeDetailDTO;
import interview.guide.modules.resume.model.ResumeEntity;
import interview.guide.modules.resume.model.ResumeListItemDTO;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

@DisplayName("简历映射器")
class ResumeMapperTest {

  private final ResumeMapper mapper = Mappers.getMapper(ResumeMapper.class);

  @Test
  @DisplayName("应映射简历列表项并保留分析状态字段")
  void shouldMapResumeListItemWithStatusFields() {
    ResumeEntity resume = new ResumeEntity();
    resume.setId(7L);
    resume.setOriginalFilename("resume.pdf");
    resume.setFileSize(2048L);
    resume.setUploadedAt(LocalDateTime.of(2026, 1, 2, 3, 4));
    resume.setAnalyzeStatus(AsyncTaskStatus.FAILED);
    resume.setAnalyzeError("parse failed");

    LocalDateTime analyzedAt = LocalDateTime.of(2026, 1, 3, 4, 5);
    ResumeListItemDTO dto = mapper.toListItemDTO(resume, 88, analyzedAt, 3);

    assertThat(dto.id()).isEqualTo(7L);
    assertThat(dto.filename()).isEqualTo("resume.pdf");
    assertThat(dto.latestScore()).isEqualTo(88);
    assertThat(dto.lastAnalyzedAt()).isEqualTo(analyzedAt);
    assertThat(dto.interviewCount()).isEqualTo(3);
    assertThat(dto.analyzeStatus()).isEqualTo(AsyncTaskStatus.FAILED);
    assertThat(dto.analyzeError()).isEqualTo("parse failed");
  }

  @Test
  @DisplayName("应使用传入的历史记录组装简历详情")
  void shouldAssembleResumeDetailWithHistories() {
    ResumeEntity resume = new ResumeEntity();
    resume.setId(9L);
    resume.setOriginalFilename("backend.docx");
    resume.setFileSize(4096L);
    resume.setContentType("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
    resume.setStorageUrl("s3://bucket/backend.docx");
    resume.setUploadedAt(LocalDateTime.of(2026, 2, 1, 10, 0));
    resume.setResumeText("Java backend resume");
    resume.setAnalyzeStatus(AsyncTaskStatus.COMPLETED);

    ResumeDetailDTO.AnalysisHistoryDTO analysis = new ResumeDetailDTO.AnalysisHistoryDTO(
        11L,
        91,
        20,
        18,
        22,
        14,
        17,
        "solid",
        LocalDateTime.of(2026, 2, 2, 10, 0),
        List.of("clear projects"),
        List.of("add metrics")
    );
    InterviewHistoryItemDTO interview = new InterviewHistoryItemDTO(
        12L,
        "session-1",
        5,
        "COMPLETED",
        "COMPLETED",
        null,
        86,
        LocalDateTime.of(2026, 2, 3, 10, 0),
        LocalDateTime.of(2026, 2, 3, 11, 0)
    );

    ResumeDetailDTO dto = mapper.toDetailDTO(resume, List.of(analysis), List.of(interview));

    assertThat(dto.id()).isEqualTo(9L);
    assertThat(dto.filename()).isEqualTo("backend.docx");
    assertThat(dto.resumeText()).isEqualTo("Java backend resume");
    assertThat(dto.analyzeStatus()).isEqualTo(AsyncTaskStatus.COMPLETED);
    assertThat(dto.analyses()).containsExactly(analysis);
    assertThat(dto.interviews()).containsExactly(interview);
  }

  @Test
  @DisplayName("应映射包含已解析优势和建议的分析历史")
  void shouldMapAnalysisHistoryWithParsedFields() {
    ResumeAnalysisEntity entity = new ResumeAnalysisEntity();
    entity.setId(21L);
    entity.setOverallScore(90);
    entity.setContentScore(19);
    entity.setStructureScore(18);
    entity.setSkillMatchScore(23);
    entity.setExpressionScore(14);
    entity.setProjectScore(16);
    entity.setSummary("good");
    entity.setAnalyzedAt(LocalDateTime.of(2026, 3, 1, 9, 0));

    ResumeDetailDTO.AnalysisHistoryDTO dto = mapper.toAnalysisHistoryDTO(
        entity,
        List.of("strong Java"),
        List.of("add impact")
    );

    assertThat(dto.id()).isEqualTo(21L);
    assertThat(dto.overallScore()).isEqualTo(90);
    assertThat(dto.strengths()).containsExactly("strong Java");
    assertThat(dto.suggestions()).containsExactly("add impact");
  }
}
