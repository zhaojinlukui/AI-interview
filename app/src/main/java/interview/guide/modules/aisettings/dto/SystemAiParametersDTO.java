package interview.guide.modules.aisettings.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record SystemAiParametersDTO(
        @Valid @NotNull ResumeWeightsDTO resumeWeights,
        @Valid @NotNull InterviewParametersDTO interview,
        @Valid @NotNull RagSearchParametersDTO ragSearch
) {

    public record ResumeWeightsDTO(
            @NotNull @Min(0) @Max(100) Integer projectWeight,
            @NotNull @Min(0) @Max(100) Integer skillMatchWeight,
            @NotNull @Min(0) @Max(100) Integer contentWeight,
            @NotNull @Min(0) @Max(100) Integer structureWeight,
            @NotNull @Min(0) @Max(100) Integer expressionWeight
    ) {
    }

    public record InterviewParametersDTO(
            @NotNull @Min(0) @Max(100) Integer resumeQuestionRatio,
            @NotNull @Min(0) @Max(100) Integer directionQuestionRatio,
            @NotNull @DecimalMin("0.0") @DecimalMax("2.0") Double questionTemperature,
            @NotNull @DecimalMin("0.0") @DecimalMax("2.0") Double followUpTemperature,
            @NotNull @DecimalMin("0.0") @DecimalMax("2.0") Double scoringTemperature,
            @NotNull @DecimalMin("0.0") @DecimalMax("2.0") Double commentTemperature
    ) {
    }

    public record RagSearchParametersDTO(
            @NotNull @Min(1) @Max(50) Integer topkShort,
            @NotNull @Min(1) @Max(50) Integer topkMedium,
            @NotNull @Min(1) @Max(50) Integer topkLong,
            @NotNull @DecimalMin("0.0") @DecimalMax("1.0") Double minScoreShort,
            @NotNull @DecimalMin("0.0") @DecimalMax("1.0") Double minScoreMedium,
            @NotNull @DecimalMin("0.0") @DecimalMax("1.0") Double minScoreLong
    ) {
    }
}
