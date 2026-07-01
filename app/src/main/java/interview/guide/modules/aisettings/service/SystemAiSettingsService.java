package interview.guide.modules.aisettings.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.aisettings.dto.SystemAiParametersDTO;
import interview.guide.modules.aisettings.dto.SystemAiParametersDTO.InterviewParametersDTO;
import interview.guide.modules.aisettings.dto.SystemAiParametersDTO.RagSearchParametersDTO;
import interview.guide.modules.aisettings.dto.SystemAiParametersDTO.ResumeWeightsDTO;
import interview.guide.modules.aisettings.model.SystemAiSettingsEntity;
import interview.guide.modules.aisettings.repository.SystemAiSettingsRepository;
import interview.guide.modules.aisettings.service.SystemAiSettingsResolver.InterviewSnapshot;
import interview.guide.modules.aisettings.service.SystemAiSettingsResolver.RagSearchSnapshot;
import interview.guide.modules.aisettings.service.SystemAiSettingsResolver.ResumeWeightsSnapshot;
import interview.guide.modules.aisettings.service.SystemAiSettingsResolver.SystemAiSettingsSnapshot;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Slf4j
@RequiredArgsConstructor
public class SystemAiSettingsService {

    private final SystemAiSettingsRepository settingsRepository;
    private final SystemAiSettingsResolver settingsResolver;

    public SystemAiParametersDTO getParameters() {
        return toDTO(settingsResolver.resolve());
    }

    @Transactional
    public void updateParameters(SystemAiParametersDTO request) {
        validate(request);
        SystemAiSettingsEntity entity = settingsRepository.findById(SystemAiSettingsEntity.SINGLETON_ID)
                .orElseGet(settingsResolver::buildDefaultEntity);

        ResumeWeightsDTO resume = request.resumeWeights();
        entity.setResumeProjectWeight(resume.projectWeight());
        entity.setResumeSkillMatchWeight(resume.skillMatchWeight());
        entity.setResumeContentWeight(resume.contentWeight());
        entity.setResumeStructureWeight(resume.structureWeight());
        entity.setResumeExpressionWeight(resume.expressionWeight());

        InterviewParametersDTO interview = request.interview();
        entity.setInterviewResumeQuestionRatio(interview.resumeQuestionRatio());
        entity.setInterviewDirectionQuestionRatio(interview.directionQuestionRatio());
        entity.setInterviewQuestionTemperature(interview.questionTemperature());
        entity.setInterviewFollowUpTemperature(interview.followUpTemperature());
        entity.setInterviewScoringTemperature(interview.scoringTemperature());
        entity.setInterviewCommentTemperature(interview.commentTemperature());

        RagSearchParametersDTO rag = request.ragSearch();
        entity.setRagTopkShort(rag.topkShort());
        entity.setRagTopkMedium(rag.topkMedium());
        entity.setRagTopkLong(rag.topkLong());
        entity.setRagMinScoreShort(rag.minScoreShort());
        entity.setRagMinScoreMedium(rag.minScoreMedium());
        entity.setRagMinScoreLong(rag.minScoreLong());

        settingsRepository.save(entity);
        log.info("System AI parameters updated");
    }

    private SystemAiParametersDTO toDTO(SystemAiSettingsSnapshot snapshot) {
        ResumeWeightsSnapshot resume = snapshot.resumeWeights();
        InterviewSnapshot interview = snapshot.interview();
        RagSearchSnapshot rag = snapshot.ragSearch();
        return new SystemAiParametersDTO(
                new ResumeWeightsDTO(
                        resume.projectWeight(),
                        resume.skillMatchWeight(),
                        resume.contentWeight(),
                        resume.structureWeight(),
                        resume.expressionWeight()
                ),
                new InterviewParametersDTO(
                        interview.resumeQuestionRatio(),
                        interview.directionQuestionRatio(),
                        interview.questionTemperature(),
                        interview.followUpTemperature(),
                        interview.scoringTemperature(),
                        interview.commentTemperature()
                ),
                new RagSearchParametersDTO(
                        rag.topkShort(),
                        rag.topkMedium(),
                        rag.topkLong(),
                        rag.minScoreShort(),
                        rag.minScoreMedium(),
                        rag.minScoreLong()
                )
        );
    }

    private void validate(SystemAiParametersDTO request) {
        if (request == null || request.resumeWeights() == null
                || request.interview() == null || request.ragSearch() == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "System AI parameters must be complete");
        }

        ResumeWeightsDTO resume = request.resumeWeights();
        validateIntRange(resume.projectWeight(), 0, 100, "Resume project weight");
        validateIntRange(resume.skillMatchWeight(), 0, 100, "Resume skill match weight");
        validateIntRange(resume.contentWeight(), 0, 100, "Resume content weight");
        validateIntRange(resume.structureWeight(), 0, 100, "Resume structure weight");
        validateIntRange(resume.expressionWeight(), 0, 100, "Resume expression weight");
        int resumeTotal = resume.projectWeight()
                + resume.skillMatchWeight()
                + resume.contentWeight()
                + resume.structureWeight()
                + resume.expressionWeight();
        if (resumeTotal != 100) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Resume weights total must be 100");
        }

        InterviewParametersDTO interview = request.interview();
        validateIntRange(interview.resumeQuestionRatio(), 0, 100, "Resume question ratio");
        validateIntRange(interview.directionQuestionRatio(), 0, 100, "Direction question ratio");
        if (interview.resumeQuestionRatio() + interview.directionQuestionRatio() != 100) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Interview question ratios total must be 100");
        }
        validateDoubleRange(interview.questionTemperature(), 0.0, 2.0, "Question temperature");
        validateDoubleRange(interview.followUpTemperature(), 0.0, 2.0, "Follow-up temperature");
        validateDoubleRange(interview.scoringTemperature(), 0.0, 2.0, "Scoring temperature");
        validateDoubleRange(interview.commentTemperature(), 0.0, 2.0, "Comment temperature");

        RagSearchParametersDTO rag = request.ragSearch();
        validateIntRange(rag.topkShort(), 1, 50, "Short question topK");
        validateIntRange(rag.topkMedium(), 1, 50, "Medium question topK");
        validateIntRange(rag.topkLong(), 1, 50, "Long question topK");
        validateDoubleRange(rag.minScoreShort(), 0.0, 1.0, "Short question min score");
        validateDoubleRange(rag.minScoreMedium(), 0.0, 1.0, "Medium question min score");
        validateDoubleRange(rag.minScoreLong(), 0.0, 1.0, "Long question min score");
    }

    private void validateIntRange(Integer value, int min, int max, String name) {
        if (value == null || value < min || value > max) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, name + " must be between " + min + " and " + max);
        }
    }

    private void validateDoubleRange(Double value, double min, double max, String name) {
        if (value == null || value < min || value > max) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, name + " must be between " + min + " and " + max);
        }
    }
}
