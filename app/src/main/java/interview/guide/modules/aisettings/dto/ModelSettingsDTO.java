package interview.guide.modules.aisettings.dto;

import lombok.Builder;

@Builder
public record ModelSettingsDTO(
    String baseUrl,
    String maskedApiKey,
    String chatModel,
    String embeddingModel,
    Integer embeddingDimensions,
    String cloudEmbeddingBaseUrl,
    String maskedCloudEmbeddingApiKey,
    String cloudEmbeddingModel,
    Integer cloudEmbeddingDimensions,
    Double temperature
) {
}
