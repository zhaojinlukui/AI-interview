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

/**
 * 简历评分服务
 * 使用AI对简历进行多维度评分，包括内容质量、结构清晰度、技能匹配度、
 * 表达能力和项目经验五个维度，最终按可配置的权重计算加权总分
 */
@Service
public class ResumeGradingService {

    private static final Logger log = LoggerFactory.getLogger(ResumeGradingService.class);

    // 各维度满分分值
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

    /**
     * AI返回的简历分析结果DTO，内部使用
     */
    record ResumeAnalysisResponseDTO(
            int overallScore,
            ScoreDetailDTO scoreDetail,
            String summary,
            List<String> strengths,
            List<SuggestionDTO> suggestions
    ) {
    }

    /**
     * AI返回的评分明细DTO
     */
    record ScoreDetailDTO(
            int contentScore,
            int structureScore,
            int skillMatchScore,
            int expressionScore,
            int projectScore
    ) {
    }

    /**
     * AI返回的修改建议DTO
     */
    record SuggestionDTO(
            String category,
            String priority,
            String issue,
            String recommendation
    ) {
    }

    /**
     * 构造函数，初始化提示词模板和输出转换器
     */
    public ResumeGradingService(
            AiClientFactory aiClientFactory,
            SystemAiSettingsResolver systemAiSettingsResolver,
            StructuredOutputInvoker structuredOutputInvoker,
            ResumeAnalysisProperties properties,
            ResourceLoader resourceLoader
    ) throws IOException {
        this.aiClientFactory = aiClientFactory;
        this.systemAiSettingsResolver = systemAiSettingsResolver;
        this.structuredOutputInvoker = structuredOutputInvoker;
        // 加载系统提示词模板
        this.systemPromptTemplate = new PromptTemplate(
                resourceLoader.getResource(properties.getSystemPromptPath())
                        .getContentAsString(StandardCharsets.UTF_8)
        );
        // 加载用户提示词模板
        this.userPromptTemplate = new PromptTemplate(
                resourceLoader.getResource(properties.getUserPromptPath())
                        .getContentAsString(StandardCharsets.UTF_8)
        );
        this.outputConverter = new BeanOutputConverter<>(ResumeAnalysisResponseDTO.class);
    }

    /**
     * 分析简历
     * 调用AI对简历文本进行多维度评分，返回包含总分、明细和建议的完整分析结果
     */
    public ResumeAnalysisResponse analyzeResume(String userId, String resumeText) {
        log.info("开始简历分析: userId={}, textLength={}", userId, resumeText.length());

        try {
            // 获取简历评分权重配置
            ResumeWeightsSnapshot weights = systemAiSettingsResolver.resolve().resumeWeights();
            // 构建提示词变量
            Map<String, Object> variables = createPromptVariables(weights, resumeText);
            String systemPrompt = systemPromptTemplate.render(variables);
            String userPrompt = userPromptTemplate.render(variables);
            // 附加输出格式说明
            String systemPromptWithFormat = systemPrompt + "\n\n" + outputConverter.getFormat();

            ResumeAnalysisResponseDTO dto;
            try {
                ChatClient chatClient = aiClientFactory.getDefaultChatClient(userId);
                // 调用AI进行结构化输出
                dto = structuredOutputInvoker.invoke(
                        chatClient,
                        systemPromptWithFormat,
                        userPrompt,
                        outputConverter,
                        ErrorCode.RESUME_ANALYSIS_FAILED,
                        "简历分析失败: ",
                        "简历分析",
                        log
                );
                log.debug(
                        "简历分析模型评分={}, 加权总分={}",
                        dto.overallScore(),
                        calculateWeightedOverallScore(dto.scoreDetail(), weights)
                );
            } catch (Exception e) {
                log.error("简历分析AI调用失败: userId={}", userId, e);
                throw new BusinessException(
                        ErrorCode.RESUME_ANALYSIS_FAILED,
                        "简历分析失败: " + e.getMessage()
                );
            }

            ResumeAnalysisResponse result = convertToResponse(dto, resumeText, weights);
            log.info("简历分析完成: userId={}, score={}", userId, result.overallScore());
            return result;
        } catch (Exception e) {
            log.error("简历分析失败: userId={}", userId, e);
            return createErrorResponse(resumeText, e.getMessage());
        }
    }

    /**
     * 将AI返回的DTO转换为业务响应对象
     */
    private ResumeAnalysisResponse convertToResponse(
            ResumeAnalysisResponseDTO dto,
            String originalText,
            ResumeWeightsSnapshot weights
    ) {
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

    /**
     * 构建提示词变量
     * 包含简历文本、各维度权重和满分值
     */
    private Map<String, Object> createPromptVariables(
            ResumeWeightsSnapshot weights,
            String resumeText
    ) {
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

    /**
     * 按配置的权重计算加权总分
     * 将各维度得分归一化后乘以对应权重，求和后限制在0-100之间
     */
    private int calculateWeightedOverallScore(
            ScoreDetailDTO scoreDetail,
            ResumeWeightsSnapshot weights
    ) {
        if (scoreDetail == null || weights == null) {
            return 0;
        }
        // 各维度得分除以满分再乘以权重，汇总后四舍五入
        double total = scoreDetail.projectScore() * weights.projectWeight()
                / (double) PROJECT_TECH_DEPTH_MAX_SCORE
                + scoreDetail.skillMatchScore() * weights.skillMatchWeight()
                / (double) SKILL_MATCH_MAX_SCORE
                + scoreDetail.contentScore() * weights.contentWeight()
                / (double) CONTENT_MAX_SCORE
                + scoreDetail.structureScore() * weights.structureWeight()
                / (double) STRUCTURE_MAX_SCORE
                + scoreDetail.expressionScore() * weights.expressionWeight()
                / (double) EXPRESSION_MAX_SCORE;
        return Math.max(0, Math.min(100, (int) Math.round(total)));
    }

    /**
     * 创建分析失败时的错误响应
     */
    private ResumeAnalysisResponse createErrorResponse(String originalText, String errorMessage) {
        return new ResumeAnalysisResponse(
                0,
                new ScoreDetail(0, 0, 0, 0, 0),
                "分析失败: " + errorMessage,
                List.of(),
                List.of(new Suggestion(
                        "系统",
                        "高",
                        "AI分析服务暂时不可用",
                        "请稍后重试，或检查AI服务配置是否正确"
                )),
                originalText
        );
    }
}