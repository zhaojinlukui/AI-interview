package interview.guide.modules.resume.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import interview.guide.infrastructure.file.FileHashService;
import interview.guide.infrastructure.mapper.ResumeMapper;
import interview.guide.modules.interview.model.ResumeAnalysisResponse;
import interview.guide.modules.interview.model.ResumeAnalysisResponse.ScoreDetail;
import interview.guide.modules.interview.model.ResumeAnalysisResponse.Suggestion;
import interview.guide.modules.resume.model.ResumeAnalysisEntity;
import interview.guide.modules.resume.model.ResumeEntity;
import interview.guide.modules.resume.repository.ResumeAnalysisRepository;
import interview.guide.modules.resume.repository.ResumeRepository;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

@DisplayName("Resume persistence")
class ResumePersistenceServiceTest {

    @Test
    @DisplayName("saveAnalysis should keep the calculated overall score")
    void saveAnalysisShouldUseAnalysisOverallScore() {
        ResumeRepository resumeRepository = mock(ResumeRepository.class);
        ResumeAnalysisRepository analysisRepository = mock(ResumeAnalysisRepository.class);
        ResumeMapper resumeMapper = mock(ResumeMapper.class);
        FileHashService fileHashService = mock(FileHashService.class);
        ResumePersistenceService service = new ResumePersistenceService(
                resumeRepository,
                analysisRepository,
                new ObjectMapper(),
                resumeMapper,
                fileHashService
        );

        ResumeEntity resume = new ResumeEntity();
        resume.setId(1L);

        ResumeAnalysisResponse analysis = new ResumeAnalysisResponse(
                83,
                new ScoreDetail(15, 10, 20, 8, 30),
                "summary",
                List.of("strength"),
                List.of(new Suggestion("project", "high", "issue", "recommendation")),
                "resume text"
        );

        ResumeAnalysisEntity mappedEntity = new ResumeAnalysisEntity();
        mappedEntity.setOverallScore(0);
        when(resumeMapper.toAnalysisEntity(analysis)).thenReturn(mappedEntity);
        when(analysisRepository.save(any(ResumeAnalysisEntity.class))).thenAnswer(invocation -> {
            ResumeAnalysisEntity entity = invocation.getArgument(0);
            entity.setId(99L);
            return entity;
        });

        ResumeAnalysisEntity saved = service.saveAnalysis(resume, analysis);

        assertThat(saved.getOverallScore()).isEqualTo(83);
    }
}
