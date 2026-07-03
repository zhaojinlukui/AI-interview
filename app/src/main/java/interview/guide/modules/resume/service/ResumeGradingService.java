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
 * 使用AI模型对简历进行多维度分析和评分
 * 包括内容、结构、技能匹配、表达和项目经验等维度
 * 支持自定义权重配置和结构化输出
 */
@Service
public class ResumeGradingService {

    private static final Logger log = LoggerFactory.getLogger(ResumeGradingService.class);

    // 各维度最大分数
    private static final int PROJECT_TECH_DEPTH_MAX_SCORE = 40;  // 项目技术深度满分
    private static final int SKILL_MATCH_MAX_SCORE = 20;         // 技能匹配度满分
    private static final int CONTENT_MAX_SCORE = 15;             // 内容质量满分
    private static final int STRUCTURE_MAX_SCORE = 15;           // 结构完整性满分
    private static final int EXPRESSION_MAX_SCORE = 10;          // 表达能力满分

    private final AiClientFactory aiClientFactory;
    // 系统AI设置解析器，获取权重配置
    private final SystemAiSettingsResolver systemAiSettingsResolver;
    private final PromptTemplate systemPromptTemplate;
    private final PromptTemplate userPromptTemplate;
    private final BeanOutputConverter<ResumeAnalysisResponseDTO> outputConverter;
    private final StructuredOutputInvoker structuredOutputInvoker;

    // AI响应数据传输对象
    record ResumeAnalysisResponseDTO(
            int overallScore,                    // 综合评分
            ScoreDetailDTO scoreDetail,          // 各维度详细评分
            String summary,                      // 分析总结
            List<String> strengths,              // 优势列表
            List<SuggestionDTO> suggestions      // 改进建议列表
    ) {
    }
    // 评分明细数据传输对象
    record ScoreDetailDTO(
            int contentScore,        // 内容质量分数
            int structureScore,      // 结构完整性分数
            int skillMatchScore,     // 技能匹配度分数
            int expressionScore,     // 表达能力分数
            int projectScore         // 项目经验分数
    ) {
    }
    // 改进建议数据传输对象
    record SuggestionDTO(
            String category,        // 建议类别
            String priority,        // 优先级
            String issue,           // 存在问题
            String recommendation   // 改进建议
    ) {
    }

    // 构造函数
    public ResumeGradingService(
            AiClientFactory aiClientFactory,
            SystemAiSettingsResolver systemAiSettingsResolver,
            StructuredOutputInvoker structuredOutputInvoker,
            ResumeAnalysisProperties properties,
            ResourceLoader resourceLoader) throws IOException {
        this.aiClientFactory = aiClientFactory;
        this.systemAiSettingsResolver = systemAiSettingsResolver;
        this.structuredOutputInvoker = structuredOutputInvoker;
        // 加载系统提示词模板文件
        this.systemPromptTemplate = new PromptTemplate(
                resourceLoader.getResource(properties.getSystemPromptPath())
                        .getContentAsString(StandardCharsets.UTF_8)
        );
        // 加载用户提示词模板文件
        this.userPromptTemplate = new PromptTemplate(
                resourceLoader.getResource(properties.getUserPromptPath())
                        .getContentAsString(StandardCharsets.UTF_8)
        );
        // 创建AI输出的类型转换器
        this.outputConverter = new BeanOutputConverter<>(ResumeAnalysisResponseDTO.class);
    }

    /**
     * 分析简历文本
     * 使用AI模型对简历进行多维度评分和分析
     */
    public ResumeAnalysisResponse analyzeResume(String resumeText) {
        log.info("开始分析简历，文本长度={}", resumeText.length());

        try {
            // 获取当前系统配置的权重快照
            ResumeWeightsSnapshot weights = systemAiSettingsResolver.resolve().resumeWeights();
            // 构建提示词变量
            Map<String, Object> variables = createPromptVariables(weights, resumeText);
            // 渲染系统提示词和用户提示词
            String systemPrompt = systemPromptTemplate.render(variables);
            String userPrompt = userPromptTemplate.render(variables);

            // 在系统提示词后附加输出格式要求
            String systemPromptWithFormat = systemPrompt + "\n\n" + outputConverter.getFormat();

            ResumeAnalysisResponseDTO dto;
            try {
                // 获取默认的AI聊天客户端
                ChatClient chatClient = aiClientFactory.getDefaultChatClient();
                // 调用AI服务并获取结构化输出
                dto = structuredOutputInvoker.invoke(
                        chatClient,
                        systemPromptWithFormat,
                        userPrompt,
                        outputConverter,
                        ErrorCode.RESUME_ANALYSIS_FAILED,
                        "简历分析失败: ",
                        "resume_analysis",
                        log
                );
                // 记录AI原始评分和加权评分，用于调试
                log.debug(
                        "简历分析模型响应：模型总分={}, 加权总分={}",
                        dto.overallScore(),
                        calculateWeightedOverallScore(dto.scoreDetail(), weights)
                );
            } catch (Exception e) {
                log.error("简历分析AI调用失败：{}", e.getMessage(), e);
                throw new BusinessException(ErrorCode.RESUME_ANALYSIS_FAILED, "简历分析失败：" + e.getMessage());
            }

            // 将AI响应转换为业务响应对象
            ResumeAnalysisResponse result = convertToResponse(dto, resumeText, weights);
            log.info("简历分析完成，综合评分={}", result.overallScore());
            return result;
        } catch (Exception e) {
            log.error("简历分析失败：{}", e.getMessage(), e);
            // 返回错误响应，保证接口不抛异常
            return createErrorResponse(resumeText, e.getMessage());
        }
    }

    /**
     * 将AI响应DTO转换为业务响应对象
     * 包括评分明细转换、建议转换和加权总分计算
     */
    private ResumeAnalysisResponse convertToResponse(
            ResumeAnalysisResponseDTO dto,
            String originalText,
            ResumeWeightsSnapshot weights) {
        // 转换评分明细
        ScoreDetail scoreDetail = new ScoreDetail(
                dto.scoreDetail().contentScore(),
                dto.scoreDetail().structureScore(),
                dto.scoreDetail().skillMatchScore(),
                dto.scoreDetail().expressionScore(),
                dto.scoreDetail().projectScore()
        );

        // 转换改进建议列表
        List<Suggestion> suggestions = dto.suggestions().stream()
                .map(s -> new Suggestion(s.category(), s.priority(), s.issue(), s.recommendation()))
                .toList();

        // 构建完整响应对象，使用加权计算的总分
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
     * 创建提示词变量
     * 将简历文本、权重配置和评分上限组装为模板变量
     */
    private Map<String, Object> createPromptVariables(ResumeWeightsSnapshot weights, String resumeText) {
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("resumeText", resumeText);                                    // 简历文本
        variables.put("projectWeight", weights.projectWeight());                    // 项目权重
        variables.put("skillMatchWeight", weights.skillMatchWeight());             // 技能匹配权重
        variables.put("contentWeight", weights.contentWeight());                   // 内容权重
        variables.put("structureWeight", weights.structureWeight());               // 结构权重
        variables.put("expressionWeight", weights.expressionWeight());             // 表达权重
        variables.put("projectTechDepthMaxScore", PROJECT_TECH_DEPTH_MAX_SCORE);   // 项目最高分
        variables.put("skillMatchMaxScore", SKILL_MATCH_MAX_SCORE);                // 技能最高分
        variables.put("contentMaxScore", CONTENT_MAX_SCORE);                       // 内容最高分
        variables.put("structureMaxScore", STRUCTURE_MAX_SCORE);                   // 结构最高分
        variables.put("expressionMaxScore", EXPRESSION_MAX_SCORE);                 // 表达最高分
        return variables;
    }

    /**
     * 计算加权综合评分
     * 根据各维度的实际得分、最高分和配置的权重计算加权总分
     * 总分范围：0-100
     */
    private int calculateWeightedOverallScore(ScoreDetailDTO scoreDetail, ResumeWeightsSnapshot weights) {
        if (scoreDetail == null || weights == null) {
            return 0;
        }
        // 加权计算：各维度得分/最高分 * 权重，然后求和
        double total = scoreDetail.projectScore() * weights.projectWeight() / (double) PROJECT_TECH_DEPTH_MAX_SCORE
                + scoreDetail.skillMatchScore() * weights.skillMatchWeight() / (double) SKILL_MATCH_MAX_SCORE
                + scoreDetail.contentScore() * weights.contentWeight() / (double) CONTENT_MAX_SCORE
                + scoreDetail.structureScore() * weights.structureWeight() / (double) STRUCTURE_MAX_SCORE
                + scoreDetail.expressionScore() * weights.expressionWeight() / (double) EXPRESSION_MAX_SCORE;
        // 确保结果在0-100范围内
        return Math.max(0, Math.min(100, (int) Math.round(total)));
    }

    /**
     * 创建错误响应
     * 当分析失败时，返回一个包含错误信息的默认响应
     */
    private ResumeAnalysisResponse createErrorResponse(String originalText, String errorMessage) {
        return new ResumeAnalysisResponse(
                0,                                         // 评分为0
                new ScoreDetail(0, 0, 0, 0, 0),           // 各维度均为0分
                "分析失败：" + errorMessage,                // 错误描述
                List.of(),                                  // 空优势列表
                List.of(new Suggestion(                     // 系统建议
                        "system",
                        "high",
                        "AI分析服务不可用",
                        "请稍后重试，或检查AI服务是否正常运行"
                )),
                originalText                                // 保留原始文本
        );
    }
}