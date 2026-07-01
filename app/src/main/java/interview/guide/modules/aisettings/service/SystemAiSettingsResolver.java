package interview.guide.modules.aisettings.service;

import interview.guide.modules.aisettings.model.SystemAiSettingsEntity;
import interview.guide.modules.aisettings.repository.SystemAiSettingsRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class SystemAiSettingsResolver {

    private final SystemAiSettingsRepository settingsRepository;

    public SystemAiSettingsSnapshot resolve() {
        return settingsRepository.findById(SystemAiSettingsEntity.SINGLETON_ID)
                .map(this::toSnapshot)
                .orElseGet(this::defaultSnapshot);
    }

    SystemAiSettingsEntity buildDefaultEntity() {
        SystemAiSettingsSnapshot defaults = defaultSnapshot();
        return SystemAiSettingsEntity.builder()
                .id(SystemAiSettingsEntity.SINGLETON_ID)
                .resumeProjectWeight(defaults.resumeWeights().projectWeight())
                .resumeSkillMatchWeight(defaults.resumeWeights().skillMatchWeight())
                .resumeContentWeight(defaults.resumeWeights().contentWeight())
                .resumeStructureWeight(defaults.resumeWeights().structureWeight())
                .resumeExpressionWeight(defaults.resumeWeights().expressionWeight())
                .interviewResumeQuestionRatio(defaults.interview().resumeQuestionRatio())
                .interviewDirectionQuestionRatio(defaults.interview().directionQuestionRatio())
                .interviewQuestionTemperature(defaults.interview().questionTemperature())
                .interviewFollowUpTemperature(defaults.interview().followUpTemperature())
                .interviewScoringTemperature(defaults.interview().scoringTemperature())
                .interviewCommentTemperature(defaults.interview().commentTemperature())
                .ragTopkShort(defaults.ragSearch().topkShort())
                .ragTopkMedium(defaults.ragSearch().topkMedium())
                .ragTopkLong(defaults.ragSearch().topkLong())
                .ragMinScoreShort(defaults.ragSearch().minScoreShort())
                .ragMinScoreMedium(defaults.ragSearch().minScoreMedium())
                .ragMinScoreLong(defaults.ragSearch().minScoreLong())
                .build();
    }

    private SystemAiSettingsSnapshot toSnapshot(SystemAiSettingsEntity entity) {
        return new SystemAiSettingsSnapshot(
                new ResumeWeightsSnapshot(
                        entity.getResumeProjectWeight(),
                        entity.getResumeSkillMatchWeight(),
                        entity.getResumeContentWeight(),
                        entity.getResumeStructureWeight(),
                        entity.getResumeExpressionWeight()
                ),
                new InterviewSnapshot(
                        entity.getInterviewResumeQuestionRatio(),
                        entity.getInterviewDirectionQuestionRatio(),
                        entity.getInterviewQuestionTemperature(),
                        entity.getInterviewFollowUpTemperature(),
                        entity.getInterviewScoringTemperature(),
                        entity.getInterviewCommentTemperature()
                ),
                new RagSearchSnapshot(
                        entity.getRagTopkShort(),
                        entity.getRagTopkMedium(),
                        entity.getRagTopkLong(),
                        entity.getRagMinScoreShort(),
                        entity.getRagMinScoreMedium(),
                        entity.getRagMinScoreLong()
                )
        );
    }

    private SystemAiSettingsSnapshot defaultSnapshot() {
        return new SystemAiSettingsSnapshot(
                new ResumeWeightsSnapshot(40, 20, 15, 15, 10),
                new InterviewSnapshot(60, 40, 0.2, 0.2, 0.2, 0.2),
                new RagSearchSnapshot(20, 12, 8, 0.18, 0.28, 0.28)
        );
    }

    public record SystemAiSettingsSnapshot(
            ResumeWeightsSnapshot resumeWeights,
            InterviewSnapshot interview,
            RagSearchSnapshot ragSearch
    ) {
    }

    public record ResumeWeightsSnapshot(
            int projectWeight,
            int skillMatchWeight,
            int contentWeight,
            int structureWeight,
            int expressionWeight
    ) {
    }

    public record InterviewSnapshot(
            int resumeQuestionRatio,
            int directionQuestionRatio,
            double questionTemperature,
            double followUpTemperature,
            double scoringTemperature,
            double commentTemperature
    ) {
    }

    public record RagSearchSnapshot(
            int topkShort,
            int topkMedium,
            int topkLong,
            double minScoreShort,
            double minScoreMedium,
            double minScoreLong
    ) {
    }
}
