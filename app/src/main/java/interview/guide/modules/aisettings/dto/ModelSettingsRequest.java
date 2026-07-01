package interview.guide.modules.aisettings.dto;

public record ModelSettingsRequest(
    String baseUrl,
    String apiKey,
    String chatModel,
    String embeddingModel,
    Integer embeddingDimensions,
    String cloudEmbeddingBaseUrl,
    String cloudEmbeddingApiKey,
    String cloudEmbeddingModel,
    Integer cloudEmbeddingDimensions,
    Double temperature
) {
}
