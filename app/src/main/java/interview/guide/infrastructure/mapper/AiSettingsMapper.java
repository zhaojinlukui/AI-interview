package interview.guide.infrastructure.mapper;

import interview.guide.modules.aisettings.dto.AsrConfigDTO;
import interview.guide.modules.aisettings.dto.AsrConfigRequest;
import interview.guide.modules.aisettings.dto.ModelSettingsDTO;
import interview.guide.modules.aisettings.dto.ModelSettingsRequest;
import interview.guide.modules.aisettings.dto.SystemAiParametersDTO;
import interview.guide.modules.aisettings.dto.TtsConfigDTO;
import interview.guide.modules.aisettings.dto.TtsConfigRequest;
import interview.guide.modules.aisettings.model.SystemAiSettingsEntity;
import interview.guide.modules.aisettings.model.UserAiSettingsEntity;
import interview.guide.modules.aisettings.service.AiSettingsResolver.AsrConfigSnapshot;
import interview.guide.modules.aisettings.service.AiSettingsResolver.ModelConfigSnapshot;
import interview.guide.modules.aisettings.service.AiSettingsResolver.TtsConfigSnapshot;
import interview.guide.modules.aisettings.service.SystemAiSettingsResolver.InterviewSnapshot;
import interview.guide.modules.aisettings.service.SystemAiSettingsResolver.RagSearchSnapshot;
import interview.guide.modules.aisettings.service.SystemAiSettingsResolver.ResumeWeightsSnapshot;
import interview.guide.modules.aisettings.service.SystemAiSettingsResolver.SystemAiSettingsSnapshot;
import org.mapstruct.BeanMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.MappingTarget;
import org.mapstruct.Named;
import org.mapstruct.NullValuePropertyMappingStrategy;
import org.mapstruct.ReportingPolicy;

@Mapper(
        componentModel = MappingConstants.ComponentModel.SPRING,
        unmappedTargetPolicy = ReportingPolicy.IGNORE
)
public interface AiSettingsMapper {

    @Mapping(target = "baseUrl", source = "model.baseUrl")
    @Mapping(target = "maskedApiKey", source = "model.apiKey", qualifiedByName = "maskApiKey")
    @Mapping(target = "chatModel", source = "model.chatModel")
    @Mapping(target = "embeddingModel", source = "embedding.embeddingModel")
    @Mapping(target = "embeddingDimensions", source = "embedding.embeddingDimensions")
    @Mapping(target = "cloudEmbeddingBaseUrl", source = "embedding.baseUrl")
    @Mapping(target = "maskedCloudEmbeddingApiKey", source = "embedding.apiKey", qualifiedByName = "maskApiKey")
    @Mapping(target = "cloudEmbeddingModel", source = "embedding.embeddingModel")
    @Mapping(target = "cloudEmbeddingDimensions", source = "embedding.embeddingDimensions")
    @Mapping(target = "temperature", source = "model.temperature")
    ModelSettingsDTO toModelSettingsDTO(ModelConfigSnapshot model, ModelConfigSnapshot embedding);

    @Mapping(target = "maskedApiKey", source = "apiKey", qualifiedByName = "maskApiKey")
    @Mapping(target = "sampleRate", source = "sampleRate", qualifiedByName = "nullToInt")
    @Mapping(target = "turnDetectionThreshold", source = "turnDetectionThreshold", qualifiedByName = "nullToFloat")
    @Mapping(
            target = "turnDetectionSilenceDurationMs",
            source = "turnDetectionSilenceDurationMs",
            qualifiedByName = "nullToInt"
    )
    AsrConfigDTO toAsrConfigDTO(AsrConfigSnapshot snapshot);

    @Mapping(target = "maskedApiKey", source = "apiKey", qualifiedByName = "maskApiKey")
    @Mapping(target = "sampleRate", source = "sampleRate", qualifiedByName = "nullToInt")
    @Mapping(target = "speechRate", source = "speechRate", qualifiedByName = "nullToFloat")
    @Mapping(target = "volume", source = "volume", qualifiedByName = "nullToInt")
    TtsConfigDTO toTtsConfigDTO(TtsConfigSnapshot snapshot);

    SystemAiParametersDTO toSystemAiParametersDTO(SystemAiSettingsSnapshot snapshot);

    SystemAiParametersDTO.ResumeWeightsDTO toResumeWeightsDTO(ResumeWeightsSnapshot snapshot);

    SystemAiParametersDTO.InterviewParametersDTO toInterviewParametersDTO(InterviewSnapshot snapshot);

    SystemAiParametersDTO.RagSearchParametersDTO toRagSearchParametersDTO(RagSearchSnapshot snapshot);

    @Mapping(target = "id", constant = "1L")
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "resumeProjectWeight", source = "resumeWeights.projectWeight")
    @Mapping(target = "resumeSkillMatchWeight", source = "resumeWeights.skillMatchWeight")
    @Mapping(target = "resumeContentWeight", source = "resumeWeights.contentWeight")
    @Mapping(target = "resumeStructureWeight", source = "resumeWeights.structureWeight")
    @Mapping(target = "resumeExpressionWeight", source = "resumeWeights.expressionWeight")
    @Mapping(target = "interviewResumeQuestionRatio", source = "interview.resumeQuestionRatio")
    @Mapping(target = "interviewDirectionQuestionRatio", source = "interview.directionQuestionRatio")
    @Mapping(target = "interviewQuestionTemperature", source = "interview.questionTemperature")
    @Mapping(target = "interviewFollowUpTemperature", source = "interview.followUpTemperature")
    @Mapping(target = "interviewScoringTemperature", source = "interview.scoringTemperature")
    @Mapping(target = "interviewCommentTemperature", source = "interview.commentTemperature")
    @Mapping(target = "ragTopkShort", source = "ragSearch.topkShort")
    @Mapping(target = "ragTopkMedium", source = "ragSearch.topkMedium")
    @Mapping(target = "ragTopkLong", source = "ragSearch.topkLong")
    @Mapping(target = "ragMinScoreShort", source = "ragSearch.minScoreShort")
    @Mapping(target = "ragMinScoreMedium", source = "ragSearch.minScoreMedium")
    @Mapping(target = "ragMinScoreLong", source = "ragSearch.minScoreLong")
    SystemAiSettingsEntity toSystemAiSettingsEntity(SystemAiSettingsSnapshot snapshot);

    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "resumeProjectWeight", source = "resumeWeights.projectWeight")
    @Mapping(target = "resumeSkillMatchWeight", source = "resumeWeights.skillMatchWeight")
    @Mapping(target = "resumeContentWeight", source = "resumeWeights.contentWeight")
    @Mapping(target = "resumeStructureWeight", source = "resumeWeights.structureWeight")
    @Mapping(target = "resumeExpressionWeight", source = "resumeWeights.expressionWeight")
    @Mapping(target = "interviewResumeQuestionRatio", source = "interview.resumeQuestionRatio")
    @Mapping(target = "interviewDirectionQuestionRatio", source = "interview.directionQuestionRatio")
    @Mapping(target = "interviewQuestionTemperature", source = "interview.questionTemperature")
    @Mapping(target = "interviewFollowUpTemperature", source = "interview.followUpTemperature")
    @Mapping(target = "interviewScoringTemperature", source = "interview.scoringTemperature")
    @Mapping(target = "interviewCommentTemperature", source = "interview.commentTemperature")
    @Mapping(target = "ragTopkShort", source = "ragSearch.topkShort")
    @Mapping(target = "ragTopkMedium", source = "ragSearch.topkMedium")
    @Mapping(target = "ragTopkLong", source = "ragSearch.topkLong")
    @Mapping(target = "ragMinScoreShort", source = "ragSearch.minScoreShort")
    @Mapping(target = "ragMinScoreMedium", source = "ragSearch.minScoreMedium")
    @Mapping(target = "ragMinScoreLong", source = "ragSearch.minScoreLong")
    void updateSystemAiSettingsEntity(
            SystemAiParametersDTO request,
            @MappingTarget SystemAiSettingsEntity entity
    );

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "userId", source = "userId")
    @Mapping(target = "modelBaseUrl", source = "model.baseUrl")
    @Mapping(target = "modelApiKey", source = "model.apiKey")
    @Mapping(target = "chatModel", source = "model.chatModel")
    @Mapping(target = "embeddingModel", source = "model.embeddingModel")
    @Mapping(target = "embeddingDimensions", source = "model.embeddingDimensions")
    @Mapping(target = "cloudEmbeddingBaseUrl", source = "cloudEmbedding.baseUrl")
    @Mapping(target = "cloudEmbeddingApiKey", source = "cloudEmbedding.apiKey")
    @Mapping(target = "cloudEmbeddingModel", source = "cloudEmbedding.embeddingModel")
    @Mapping(target = "cloudEmbeddingDimensions", source = "cloudEmbedding.embeddingDimensions")
    @Mapping(target = "temperature", source = "model.temperature")
    @Mapping(target = "asrUrl", source = "asr.url")
    @Mapping(target = "asrModel", source = "asr.model")
    @Mapping(target = "asrApiKey", ignore = true)
    @Mapping(target = "asrLanguage", source = "asr.language")
    @Mapping(target = "asrFormat", source = "asr.format")
    @Mapping(target = "asrSampleRate", source = "asr.sampleRate")
    @Mapping(target = "asrEnableTurnDetection", source = "asr.enableTurnDetection")
    @Mapping(target = "asrTurnDetectionType", source = "asr.turnDetectionType")
    @Mapping(target = "asrTurnDetectionThreshold", source = "asr.turnDetectionThreshold")
    @Mapping(target = "asrTurnDetectionSilenceDurationMs", source = "asr.turnDetectionSilenceDurationMs")
    @Mapping(target = "ttsModel", source = "tts.model")
    @Mapping(target = "ttsApiKey", ignore = true)
    @Mapping(target = "ttsVoice", source = "tts.voice")
    @Mapping(target = "ttsFormat", source = "tts.format")
    @Mapping(target = "ttsSampleRate", source = "tts.sampleRate")
    @Mapping(target = "ttsMode", source = "tts.mode")
    @Mapping(target = "ttsLanguageType", source = "tts.languageType")
    @Mapping(target = "ttsSpeechRate", source = "tts.speechRate")
    @Mapping(target = "ttsVolume", source = "tts.volume")
    UserAiSettingsEntity toUserAiSettingsEntity(
            String userId,
            ModelConfigSnapshot model,
            ModelConfigSnapshot cloudEmbedding,
            AsrConfigSnapshot asr,
            TtsConfigSnapshot tts
    );

    @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "userId", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "modelBaseUrl", ignore = true)
    @Mapping(target = "modelApiKey", ignore = true)
    @Mapping(target = "chatModel", ignore = true)
    @Mapping(target = "embeddingModel", ignore = true)
    @Mapping(target = "embeddingDimensions", ignore = true)
    @Mapping(target = "cloudEmbeddingBaseUrl", ignore = true)
    @Mapping(target = "cloudEmbeddingApiKey", ignore = true)
    @Mapping(target = "cloudEmbeddingModel", ignore = true)
    @Mapping(target = "cloudEmbeddingDimensions", ignore = true)
    void patchModelSettings(ModelSettingsRequest request, @MappingTarget UserAiSettingsEntity entity);

    @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "userId", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "asrUrl", ignore = true)
    @Mapping(target = "asrModel", ignore = true)
    @Mapping(target = "asrApiKey", ignore = true)
    @Mapping(target = "asrLanguage", ignore = true)
    @Mapping(target = "asrFormat", ignore = true)
    @Mapping(target = "asrTurnDetectionType", ignore = true)
    @Mapping(target = "asrSampleRate", source = "sampleRate")
    @Mapping(target = "asrEnableTurnDetection", source = "enableTurnDetection")
    @Mapping(target = "asrTurnDetectionThreshold", source = "turnDetectionThreshold")
    @Mapping(target = "asrTurnDetectionSilenceDurationMs", source = "turnDetectionSilenceDurationMs")
    void patchAsrConfig(AsrConfigRequest request, @MappingTarget UserAiSettingsEntity entity);

    @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "userId", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "ttsModel", ignore = true)
    @Mapping(target = "ttsApiKey", ignore = true)
    @Mapping(target = "ttsVoice", ignore = true)
    @Mapping(target = "ttsFormat", ignore = true)
    @Mapping(target = "ttsMode", ignore = true)
    @Mapping(target = "ttsLanguageType", ignore = true)
    @Mapping(target = "ttsSampleRate", source = "sampleRate")
    @Mapping(target = "ttsSpeechRate", source = "speechRate")
    @Mapping(target = "ttsVolume", source = "volume")
    void patchTtsConfig(TtsConfigRequest request, @MappingTarget UserAiSettingsEntity entity);

    @Named("maskApiKey")
    default String maskApiKey(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            return "";
        }
        if (apiKey.length() <= 8) {
            return "****";
        }
        return apiKey.substring(0, 4) + "****" + apiKey.substring(apiKey.length() - 4);
    }

    @Named("nullToInt")
    default int nullToInt(Integer value) {
        return value == null ? 0 : value;
    }

    @Named("nullToFloat")
    default float nullToFloat(Float value) {
        return value == null ? 0F : value;
    }
}
