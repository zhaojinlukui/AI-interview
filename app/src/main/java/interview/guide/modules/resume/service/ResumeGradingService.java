package interview.guide.modules.resume.service;

import interview.guide.common.ai.AiClientFactory;
import interview.guide.common.ai.StructuredOutputInvoker;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.aisettings.service.SystemAiSettingsResolver;
import interview.guide.modules.aisettings.service.SystemAiSettingsResolver.ResumeWeightsSnapshot;
import interview.guide.modules.interview.model.ResumeAnalysisResponse;
import interview.guide.modules.interview.model.ResumeAnalysisResponse.ScoreDetail;
import interview.guide.modules.interview.model.ResumeAnalysisResponse.Suggestion;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

@Service
public class ResumeGradingService {

    private static final Logger log = LoggerFactory.getLogger(ResumeGradingService.class);
    private static final int PROJECT_TECH_DEPTH_MAX_SCORE = 40;
    private static final int SKILL_MATCH_MAX_SCORE = 20;
    private static final int CONTENT_MAX_SCORE = 15;
    private static final int STRUCTURE_MAX_SCORE = 15;
    private static final int EXPRESSION_MAX_SCORE = 10;

    private final AiClientFactory aiClientFactory;
    private final SystemAiSettingsResolver systemAiSettingsResolver;
    private final PromptTemplate systemPromptTemplate;
    private final PromptTemplate userPromptTemplate;
    private final BeanOutputConverter<ResumeAnalysisResponseDTO> outputConverter;
    private final StructuredOutputInvoker structuredOutputInvoker;

    record ResumeAnalysisResponseDTO(
            int overallScore,
            ScoreDetailDTO scoreDetail,
            String summary,
            List<String> strengths,
            List<SuggestionDTO> suggestions
    ) {
    }

    record ScoreDetailDTO(
            int contentScore,
            int structureScore,
            int skillMatchScore,
            int expressionScore,
            int projectScore
    ) {
    }

    record SuggestionDTO(
            String category,
            String priority,
            String issue,
            String recommendation
    ) {
    }

    public ResumeGradingService(
            AiClientFactory aiClientFactory,
            SystemAiSettingsResolver systemAiSettingsResolver,
            StructuredOutputInvoker structuredOutputInvoker,
            ResumeAnalysisProperties properties,
            ResourceLoader resourceLoader) throws IOException {
        this.aiClientFactory = aiClientFactory;
        this.systemAiSettingsResolver = systemAiSettingsResolver;
        this.structuredOutputInvoker = structuredOutputInvoker;
        this.systemPromptTemplate = new PromptTemplate(
                resourceLoader.getResource(properties.getSystemPromptPath())
                        .getContentAsString(StandardCharsets.UTF_8)
        );
        this.userPromptTemplate = new PromptTemplate(
                resourceLoader.getResource(properties.getUserPromptPath())
                        .getContentAsString(StandardCharsets.UTF_8)
        );
        this.outputConverter = new BeanOutputConverter<>(ResumeAnalysisResponseDTO.class);
    }

    public ResumeAnalysisResponse analyzeResume(String resumeText) {
        log.info("Starting resume analysis, textLength={}", resumeText.length());

        try {
            ResumeWeightsSnapshot weights = systemAiSettingsResolver.resolve().resumeWeights();
            Map<String, Object> variables = createPromptVariables(weights, resumeText);
            String systemPrompt = systemPromptTemplate.render(variables);
            String userPrompt = userPromptTemplate.render(variables);

            String systemPromptWithFormat = systemPrompt + "\n\n" + outputConverter.getFormat();

            ResumeAnalysisResponseDTO dto;
            try {
                ChatClient chatClient = aiClientFactory.getDefaultChatClient();
                dto = structuredOutputInvoker.invoke(
                        chatClient,
                        systemPromptWithFormat,
                        userPrompt,
                        outputConverter,
                        ErrorCode.RESUME_ANALYSIS_FAILED,
                        "resume analysis failed: ",
                        "resume_analysis",
                        log
                );
                log.debug(
                        "Resume analysis model response: modelOverallScore={}, weightedOverallScore={}",
                        dto.overallScore(),
                        calculateWeightedOverallScore(dto.scoreDetail(), weights)
                );
            } catch (Exception e) {
                log.error("Resume analysis AI call failed: {}", e.getMessage(), e);
                throw new BusinessException(ErrorCode.RESUME_ANALYSIS_FAILED, "resume analysis failed: " + e.getMessage());
            }

            ResumeAnalysisResponse result = convertToResponse(dto, resumeText, weights);
            log.info("Resume analysis completed, overallScore={}", result.overallScore());
            return result;
        } catch (Exception e) {
            log.error("Resume analysis failed: {}", e.getMessage(), e);
            return createErrorResponse(resumeText, e.getMessage());
        }
    }

    private ResumeAnalysisResponse convertToResponse(
            ResumeAnalysisResponseDTO dto,
            String originalText,
            ResumeWeightsSnapshot weights) {
        ScoreDetail scoreDetail = new ScoreDetail(
                dto.scoreDetail().contentScore(),
                dto.scoreDetail().structureScore(),
                dto.scoreDetail().skillMatchScore(),
                dto.scoreDetail().expressionScore(),
                dto.scoreDetail().projectScore()
        );

        List<Suggestion> suggestions = dto.suggestions().stream()
                .map(s -> new Suggestion(s.category(), s.priority(), s.issue(), s.recommendation()))
                .toList();

        return new ResumeAnalysisResponse(
                calculateWeightedOverallScore(dto.scoreDetail(), weights),
                scoreDetail,
                dto.summary(),
                dto.strengths(),
                suggestions,
                originalText
        );
    }

    private Map<String, Object> createPromptVariables(ResumeWeightsSnapshot weights, String resumeText) {
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("resumeText", resumeText);
        variables.put("projectWeight", weights.projectWeight());
        variables.put("skillMatchWeight", weights.skillMatchWeight());
        variables.put("contentWeight", weights.contentWeight());
        variables.put("structureWeight", weights.structureWeight());
        variables.put("expressionWeight", weights.expressionWeight());
        variables.put("projectTechDepthMaxScore", PROJECT_TECH_DEPTH_MAX_SCORE);
        variables.put("skillMatchMaxScore", SKILL_MATCH_MAX_SCORE);
        variables.put("contentMaxScore", CONTENT_MAX_SCORE);
        variables.put("structureMaxScore", STRUCTURE_MAX_SCORE);
        variables.put("expressionMaxScore", EXPRESSION_MAX_SCORE);
        return variables;
    }

    private int calculateWeightedOverallScore(ScoreDetailDTO scoreDetail, ResumeWeightsSnapshot weights) {
        if (scoreDetail == null || weights == null) {
            return 0;
        }
        double total = scoreDetail.projectScore() * weights.projectWeight() / (double) PROJECT_TECH_DEPTH_MAX_SCORE
                + scoreDetail.skillMatchScore() * weights.skillMatchWeight() / (double) SKILL_MATCH_MAX_SCORE
                + scoreDetail.contentScore() * weights.contentWeight() / (double) CONTENT_MAX_SCORE
                + scoreDetail.structureScore() * weights.structureWeight() / (double) STRUCTURE_MAX_SCORE
                + scoreDetail.expressionScore() * weights.expressionWeight() / (double) EXPRESSION_MAX_SCORE;
        return Math.max(0, Math.min(100, (int) Math.round(total)));
    }

    private ResumeAnalysisResponse createErrorResponse(String originalText, String errorMessage) {
        return new ResumeAnalysisResponse(
                0,
                new ScoreDetail(0, 0, 0, 0, 0),
                "Analysis failed: " + errorMessage,
                List.of(),
                List.of(new Suggestion(
                        "system",
                        "high",
                        "AI analysis service unavailable",
                        "Please try again later or check whether the AI service is running"
                )),
                originalText
        );
    }
}
