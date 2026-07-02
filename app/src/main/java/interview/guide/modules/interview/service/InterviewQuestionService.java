package interview.guide.modules.interview.service;

import interview.guide.common.ai.AiClientFactory;
import interview.guide.common.ai.PromptSanitizer;
import interview.guide.common.ai.PromptSecurityConstants;
import interview.guide.common.ai.StructuredOutputInvoker;
import interview.guide.common.constant.CommonConstants.InterviewDefaults;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.aisettings.service.SystemAiSettingsResolver;
import interview.guide.modules.aisettings.service.SystemAiSettingsResolver.InterviewSnapshot;
import interview.guide.modules.interview.model.HistoricalQuestion;
import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.interview.skill.InterviewSkillService;
import interview.guide.modules.interview.skill.InterviewSkillService.CategoryDTO;
import interview.guide.modules.interview.skill.InterviewSkillService.SkillCategoryDTO;
import interview.guide.modules.interview.skill.InterviewSkillService.SkillDTO;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

/**
 * 面试题目生成服务
 * 基于方向、简历内容和难度等级，利用AI生成面试题目及追问，
 * 支持有简历和无简历两种模式，包含并发生成、降级兜底和数量控制机制
 */
@Service
public class InterviewQuestionService {

    private static final Logger log = LoggerFactory.getLogger(InterviewQuestionService.class);

    private static final String DEFAULT_QUESTION_TYPE = "综合"; // 默认题目类型
    private static final int MAX_FOLLOW_UP_COUNT = 2; // 最大追问数量上限
    // 无简历模式下的系统提示词追加内容，要求AI不引用简历相关信息
    private static final String GENERIC_MODE_SYSTEM_APPEND = """
        \n\n# 通用面试模式
        本次面试无候选人简历，请出该方向的标准面试题。
        - 禁止出现"你在简历中提到..."、"你在项目中..."等暗示存在简历的表述
        - 问题表述应与简历无关，直接考察该方向的技术能力
        """;

    // 难度等级对应的描述信息
    private static final Map<String, String> DIFFICULTY_DESCRIPTIONS = Map.of(
            "junior", "校招/0-1年经验。考察基础概念和简单应用。",
            "mid", "1-3年经验。考察原理理解和实战经验。",
            "senior", "3年+经验。考察架构设计和深度调优。"
    );
    // 通用兜底题目列表，当AI生成失败时使用
    private static final String[][] GENERIC_FALLBACK_QUESTIONS = {
            {"请描述一个你主导解决的技术难题，你的分析思路是什么？", "综合", "综合能力"},
            {"你在做技术方案选型时，通常考虑哪些因素？请举例说明。", "综合", "综合能力"},
            {"请分享一次你处理线上故障的经历，从发现到修复的完整过程。", "综合", "综合能力"},
            {"你如何保证代码质量？介绍你实践过的有效手段。", "综合", "综合能力"},
            {"描述一个你做过的技术优化案例，优化的动机、方案和效果。", "综合", "综合能力"},
            {"你在团队协作中遇到过最大的分歧是什么？如何解决的？", "综合", "综合能力"},
    };

    private final PromptTemplate skillSystemPromptTemplate;
    private final PromptTemplate skillUserPromptTemplate;
    private final PromptTemplate resumeSystemPromptTemplate;
    private final PromptTemplate resumeUserPromptTemplate;
    private final BeanOutputConverter<QuestionListDTO> outputConverter;
    private final StructuredOutputInvoker structuredOutputInvoker;
    private final InterviewSkillService skillService;
    private final AiClientFactory aiClientFactory;
    private final PromptSanitizer promptSanitizer;
    private final SystemAiSettingsResolver systemAiSettingsResolver;
    private final ExecutorService questionExecutor;
    private final int followUpCount;

    /**
     * AI返回的题目列表DTO，内部使用
     */
    private record QuestionListDTO(List<QuestionDTO> questions) {
    }

    /**
     * AI返回的单条题目DTO，包含问题文本、类型、分类、主题摘要和追问列表
     */
    private record QuestionDTO(
            String question,
            String type,
            String category,
            String topicSummary,
            List<String> followUps
    ) {
    }

    /**
     * 构造函数，初始化各类提示词模板、执行器和配置参数
     */
    public InterviewQuestionService(
            StructuredOutputInvoker structuredOutputInvoker,
            InterviewSkillService skillService,
            InterviewQuestionProperties properties,
            ResourceLoader resourceLoader,
            AiClientFactory aiClientFactory,
            PromptSanitizer promptSanitizer,
            SystemAiSettingsResolver systemAiSettingsResolver
    ) throws IOException {
        this.structuredOutputInvoker = structuredOutputInvoker;
        this.skillService = skillService;
        this.aiClientFactory = aiClientFactory;
        this.promptSanitizer = promptSanitizer;
        this.systemAiSettingsResolver = systemAiSettingsResolver;
        this.questionExecutor = Executors.newVirtualThreadPerTaskExecutor();
        this.skillSystemPromptTemplate = loadTemplate(
                resourceLoader,
                properties.getQuestionSystemPromptPath()
        );
        this.skillUserPromptTemplate = loadTemplate(
                resourceLoader,
                properties.getQuestionUserPromptPath()
        );
        this.resumeSystemPromptTemplate = loadTemplate(
                resourceLoader,
                properties.getResumeQuestionSystemPromptPath()
        );
        this.resumeUserPromptTemplate = loadTemplate(
                resourceLoader,
                properties.getResumeQuestionUserPromptPath()
        );
        this.outputConverter = new BeanOutputConverter<>(QuestionListDTO.class);
        this.followUpCount = Math.max(0, Math.min(properties.getFollowUpCount(), MAX_FOLLOW_UP_COUNT));
    }

    /**
     * 从资源文件加载提示词模板
     */
    private static PromptTemplate loadTemplate(ResourceLoader loader, String location)
            throws IOException {
        return new PromptTemplate(
                loader.getResource(location).getContentAsString(StandardCharsets.UTF_8)
        );
    }

    /**
     * Bean销毁时关闭线程池
     */
    @PreDestroy
    void destroy() {
        questionExecutor.shutdownNow();
    }

    /**
     * 根据方向生成面试题目
     * 根据是否有简历选择不同的生成策略，有简历时并发调用简历题目和方向题目生成
     */
    public List<InterviewQuestionDTO> generateQuestionsBySkill(
            String skillId,
            String difficulty,
            String resumeText,
            int questionCount,
            List<HistoricalQuestion> historicalQuestions,
            List<CategoryDTO> customCategories,
            String jdText
    ) {
        // 解析方向信息和难度描述
        SkillDTO skill = resolveSkill(skillId, customCategories, jdText);
        String difficultyDesc = resolveDifficulty(difficulty);
        // 获取AI客户端
        ChatClient questionChatClient = aiClientFactory.getPlainChatClient();
        // 解析系统AI配置
        InterviewSnapshot settings = systemAiSettingsResolver.resolve().interview();
        // 判断是否有简历内容
        boolean hasResume = resumeText != null && !resumeText.isBlank();
        // 构建历史问题文本，用于避免重复出题
        String historicalSection = buildHistoricalSection(historicalQuestions);
        // 计算需要生成的主问题数量（不含追问）
        int mainQuestionCount = calculateMainQuestionCount(questionCount);

        // 无简历模式：仅生成方向题目
        if (!hasResume) {
            List<InterviewQuestionDTO> questions = generateDirectionOnly(
                    questionChatClient,
                    skill,
                    difficultyDesc,
                    mainQuestionCount,
                    historicalSection,
                    settings
            );
            return finalizeGeneratedQuestions(skill, questions, mainQuestionCount, questionCount);
        }

        // 有简历模式：计算简历题目和方向题目的分配数量
        int resumeCount = calculateResumeQuestionCount(
                mainQuestionCount,
                settings.resumeQuestionRatio()
        );
        int directionCount = mainQuestionCount - resumeCount;
        log.info(
                "面试题目分配: 方向ID={}, 总题目数={}, 主问题预算={}, 简历题目数={}, 方向题目数={}",
                skillId,
                questionCount,
                mainQuestionCount,
                resumeCount,
                directionCount
        );

        // 异步生成简历相关题目
        CompletableFuture<List<InterviewQuestionDTO>> resumeFuture = CompletableFuture.supplyAsync(
                () -> generateResumeQuestions(
                        questionChatClient,
                        resumeText,
                        resumeCount,
                        skill,
                        difficultyDesc,
                        historicalSection,
                        settings
                ),
                questionExecutor
        );
        // 异步生成方向题目
        CompletableFuture<List<InterviewQuestionDTO>> directionFuture = CompletableFuture.supplyAsync(
                () -> generateDirectionOnly(
                        questionChatClient,
                        skill,
                        difficultyDesc,
                        directionCount,
                        historicalSection,
                        settings
                ),
                questionExecutor
        );

        List<InterviewQuestionDTO> resumeQuestions;
        List<InterviewQuestionDTO> directionQuestions;
        try {
            // 等待简历题目生成完成
            resumeQuestions = resumeFuture.join();
        } catch (CompletionException e) {
            // 简历题目生成失败，取消方向题目生成任务，全部改用方向题目
            log.error("基于简历的题目生成失败，降级为纯方向题目", e.getCause());
            directionFuture.cancel(true);
            List<InterviewQuestionDTO> questions = generateDirectionOnly(
                    questionChatClient,
                    skill,
                    difficultyDesc,
                    mainQuestionCount,
                    historicalSection,
                    settings
            );
            return finalizeGeneratedQuestions(skill, questions, mainQuestionCount, questionCount);
        }

        try {
            // 等待方向题目生成完成
            directionQuestions = directionFuture.join();
        } catch (CompletionException e) {
            // 方向题目生成失败，仅使用简历题目
            log.error("方向题目生成失败，仅使用简历题目", e.getCause());
            if (resumeQuestions.isEmpty()) {
                // 简历题目也为空时使用兜底题目
                return finalizeGeneratedQuestions(
                        skill,
                        generateFallbackQuestions(skill, mainQuestionCount),
                        mainQuestionCount,
                        questionCount
                );
            }
            return finalizeGeneratedQuestions(skill, resumeQuestions, mainQuestionCount, questionCount);
        }

        // 两边都为空时使用兜底题目
        if (resumeQuestions.isEmpty() && directionQuestions.isEmpty()) {
            return finalizeGeneratedQuestions(
                    skill,
                    generateFallbackQuestions(skill, mainQuestionCount),
                    mainQuestionCount,
                    questionCount
            );
        }

        // 合并简历题目和方向题目
        List<InterviewQuestionDTO> merged = mergeQuestionBatches(resumeQuestions, directionQuestions);
        return finalizeGeneratedQuestions(skill, merged, mainQuestionCount, questionCount);
    }

    /**
     * 计算需要生成的主问题数量
     * 根据追问数量将总题数换算为主问题数，每个主问题附带若干追问
     */
    private int calculateMainQuestionCount(int totalQuestionCount) {
        if (totalQuestionCount <= 0) {
            return 0;
        }
        // 无追问时，主问题数等于总题数
        if (followUpCount <= 0) {
            return totalQuestionCount;
        }
        // 每组问题 = 1个主问题 + followUpCount个追问
        int questionGroupSize = followUpCount + 1;
        return Math.max(1, (int) Math.ceil(totalQuestionCount / (double) questionGroupSize));
    }

    /**
     * 最终化生成的题目列表
     * 补全不足的主问题、规范化追问结构、按总数量截断
     */
    private List<InterviewQuestionDTO> finalizeGeneratedQuestions(
            SkillDTO skill,
            List<InterviewQuestionDTO> questions,
            int expectedMainCount,
            int requestedTotalCount
    ) {
        List<InterviewQuestionDTO> safeQuestions = questions != null ? questions : List.of();
        // 补全缺失的主问题
        List<InterviewQuestionDTO> completedQuestions = fillMissingMainQuestions(
                skill,
                safeQuestions,
                expectedMainCount
        );
        // 规范化追问结构后按总数量截断
        return capToTotalCount(
                normalizeQuestionsWithFollowUps(completedQuestions),
                requestedTotalCount
        );
    }

    /**
     * 按最大总数量截断题目列表
     */
    private List<InterviewQuestionDTO> capToTotalCount(
            List<InterviewQuestionDTO> questions,
            int maxTotalCount
    ) {
        if (maxTotalCount <= 0 || questions == null || questions.isEmpty()) {
            return List.of();
        }
        if (questions.size() <= maxTotalCount) {
            return questions;
        }
        return new ArrayList<>(questions.subList(0, maxTotalCount));
    }

    /**
     * 基于简历生成题目
     * 使用简历模板调用AI生成与候选人经历相关的问题
     */
    private List<InterviewQuestionDTO> generateResumeQuestions(
            ChatClient questionClient,
            String resumeText,
            int questionCount,
            SkillDTO skill,
            String difficultyDesc,
            String historicalSection,
            InterviewSnapshot settings
    ) {
        if (questionCount <= 0) {
            return List.of();
        }
        // 构建提示词变量
        Map<String, Object> variables = new HashMap<>();
        variables.put("questionCount", questionCount);
        variables.put("followUpCount", followUpCount);
        variables.put("skillName", skill.name());
        variables.put("skillDescription", skill.description() != null ? skill.description() : "");
        variables.put("difficultyDescription", difficultyDesc);
        variables.put("resumeText", resumeText);
        variables.put("historicalSection", historicalSection);

        // 渲染系统提示词并附加方向人设和输出格式
        String systemPrompt = resumeSystemPromptTemplate.render()
                + buildSkillPersonaSection(skill)
                + "\n\n" + outputConverter.getFormat();
        String userPrompt = resumeUserPromptTemplate.render(variables);

        // 调用AI生成题目
        QuestionListDTO dto = structuredOutputInvoker.invoke(
                questionClient,
                systemPrompt,
                userPrompt,
                outputConverter,
                chatOptions(settings.questionTemperature()),
                ErrorCode.INTERVIEW_QUESTION_GENERATION_FAILED,
                "简历题目生成失败: ",
                "简历题目",
                log
        );

        return capToMainCount(convertToQuestions(dto), questionCount);
    }

    /**
     * 仅基于方向生成题目
     * 使用方向模板调用AI生成通用技术问题
     */
    private List<InterviewQuestionDTO> generateDirectionOnly(
            ChatClient questionClient,
            SkillDTO skill,
            String difficultyDesc,
            int questionCount,
            String historicalSection,
            InterviewSnapshot settings
    ) {
        if (questionCount <= 0) {
            return List.of();
        }
        // 计算各分类的题目分配
        Map<String, Integer> allocation = skillService.calculateAllocation(
                skill.categories(),
                questionCount
        );
        String allocationTable = skillService.buildAllocationDescription(
                allocation,
                skill.categories()
        );

        try {
            // 渲染系统提示词并附加方向人设、通用模式说明和输出格式
            String systemPrompt = skillSystemPromptTemplate.render()
                    + buildSkillPersonaSection(skill)
                    + GENERIC_MODE_SYSTEM_APPEND
                    + outputConverter.getFormat();

            // 构建用户提示词变量
            Map<String, Object> variables = new HashMap<>();
            variables.put("questionCount", questionCount);
            variables.put("followUpCount", followUpCount);
            variables.put("difficultyDescription", difficultyDesc);
            variables.put("skillName", skill.name());
            variables.put("skillDescription", skill.description() != null ? skill.description() : "");
            variables.put("allocationTable", allocationTable);
            variables.put("historicalSection", historicalSection);
            variables.put("referenceSection", skillService.buildReferenceSection(skill, allocation));
            variables.put("jdSection", buildJdSection(skill.sourceJd()));
            String userPrompt = skillUserPromptTemplate.render(variables);

            // 调用AI生成题目
            QuestionListDTO dto = structuredOutputInvoker.invoke(
                    questionClient,
                    systemPrompt,
                    userPrompt,
                    outputConverter,
                    chatOptions(settings.questionTemperature()),
                    ErrorCode.INTERVIEW_QUESTION_GENERATION_FAILED,
                    "方向题目生成失败: ",
                    "方向题目",
                    log
            );

            List<InterviewQuestionDTO> questions = capToMainCount(convertToQuestions(dto), questionCount);
            // 如果AI没有生成任何主问题，使用兜底题目
            if (questions.stream().noneMatch(question -> !question.isFollowUp())) {
                return generateFallbackQuestions(skill, questionCount);
            }
            return questions;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            // 异常时使用兜底题目
            log.error("方向题目生成失败，使用兜底题目", e);
            return generateFallbackQuestions(skill, questionCount);
        }
    }

    /**
     * 规范化题目列表中的追问结构
     * 将平铺的题目列表按主问题和追问分组，补全缺失的追问并重新编号
     */
    private List<InterviewQuestionDTO> normalizeQuestionsWithFollowUps(
            List<InterviewQuestionDTO> mainQuestions
    ) {
        if (mainQuestions == null || mainQuestions.isEmpty()) {
            return List.of();
        }
        // 无追问时仅重新编号
        if (followUpCount == 0) {
            return reindexQuestions(mainQuestions);
        }

        // 提取所有非追问的主问题
        List<InterviewQuestionDTO> mains = mainQuestions.stream()
                .filter(question -> !question.isFollowUp())
                .toList();

        List<InterviewQuestionDTO> result = new ArrayList<>();
        int index = 0;
        for (InterviewQuestionDTO main : mains) {
            int mainIndex = index;
            // 添加主问题
            result.add(InterviewQuestionDTO.create(
                    index++,
                    main.question(),
                    main.type(),
                    main.category(),
                    main.topicSummary(),
                    false,
                    null
            ));

            // 提取已有的追问
            List<String> followUps = extractGeneratedFollowUps(mainQuestions, main);
            // 补全不足的追问
            if (followUps.size() < followUpCount) {
                followUps = fillMissingFollowUps(main.question(), followUps);
            }
            // 按追问数量添加入结果列表
            for (int order = 0; order < Math.min(followUpCount, followUps.size()); order++) {
                result.add(InterviewQuestionDTO.create(
                        index++,
                        followUps.get(order),
                        main.type(),
                        buildFollowUpCategory(main.category(), order + 1),
                        null,
                        true,
                        mainIndex
                ));
            }
        }
        return result;
    }

    /**
     * 合并两批题目列表
     */
    private List<InterviewQuestionDTO> mergeQuestionBatches(
            List<InterviewQuestionDTO> first,
            List<InterviewQuestionDTO> second
    ) {
        if (first.isEmpty()) {
            return second;
        }
        if (second.isEmpty()) {
            return first;
        }
        List<InterviewQuestionDTO> merged = new ArrayList<>();
        merged.addAll(first);
        merged.addAll(second);
        return merged;
    }

    /**
     * 补全缺失的主问题
     * 当AI生成的主问题数量不足时，使用兜底题目填充
     */
    private List<InterviewQuestionDTO> fillMissingMainQuestions(
            SkillDTO skill,
            List<InterviewQuestionDTO> questions,
            int expectedMainCount
    ) {
        long currentMainCount = questions.stream()
                .filter(question -> !question.isFollowUp())
                .count();
        if (currentMainCount >= expectedMainCount) {
            return questions;
        }

        List<InterviewQuestionDTO> filled = new ArrayList<>(questions);
        // 使用兜底题目补足缺少的主问题数量
        List<InterviewQuestionDTO> fallbackQuestions = generateFallbackQuestions(
                skill,
                expectedMainCount - (int) currentMainCount
        );
        filled.addAll(fallbackQuestions);
        return filled;
    }

    /**
     * 重新编号题目列表，仅保留主问题并重新分配序号
     */
    private List<InterviewQuestionDTO> reindexQuestions(List<InterviewQuestionDTO> questions) {
        List<InterviewQuestionDTO> reindexed = new ArrayList<>();
        int index = 0;
        for (InterviewQuestionDTO question : questions) {
            // 跳过追问
            if (question.isFollowUp()) {
                continue;
            }
            reindexed.add(InterviewQuestionDTO.create(
                    index++,
                    question.question(),
                    question.type(),
                    question.category(),
                    question.topicSummary(),
                    false,
                    null
            ));
        }
        return reindexed;
    }

    /**
     * 从题目列表中提取某个主问题后面的已生成追问
     */
    private List<String> extractGeneratedFollowUps(
            List<InterviewQuestionDTO> questions,
            InterviewQuestionDTO mainQuestion
    ) {
        List<String> followUps = new ArrayList<>();
        boolean afterMainQuestion = false;
        for (InterviewQuestionDTO question : questions) {
            if (question == mainQuestion) {
                afterMainQuestion = true;
                continue;
            }
            if (!afterMainQuestion) {
                continue;
            }
            // 遇到下一个主问题时停止
            if (!question.isFollowUp()) {
                break;
            }
            if (question.question() != null && !question.question().isBlank()) {
                followUps.add(question.question().trim());
            }
            if (followUps.size() >= followUpCount) {
                break;
            }
        }
        return followUps;
    }

    /**
     * 补全缺失的追问，不足部分使用默认追问填充
     */
    private List<String> fillMissingFollowUps(String mainQuestion, List<String> generatedFollowUps) {
        List<String> followUps = new ArrayList<>(
                generatedFollowUps != null ? generatedFollowUps : List.of()
        );
        for (int order = followUps.size() + 1; order <= followUpCount; order++) {
            followUps.add(buildDefaultFollowUp(mainQuestion, order));
        }
        return followUps;
    }

    /**
     * 解析方向信息
     * 如果是自定义方向则构建自定义方向对象，否则从方向服务获取
     */
    private SkillDTO resolveSkill(String skillId, List<CategoryDTO> customCategories, String jdText) {
        if (InterviewSkillService.CUSTOM_SKILL_ID.equals(skillId)
                && customCategories != null
                && !customCategories.isEmpty()) {
            return skillService.buildCustomSkill(customCategories, jdText != null ? jdText : "");
        }
        return skillService.getSkill(skillId);
    }

    /**
     * 解析难度等级描述
     */
    private String resolveDifficulty(String difficulty) {
        return DIFFICULTY_DESCRIPTIONS.getOrDefault(
                difficulty != null ? difficulty : InterviewDefaults.DIFFICULTY,
                DIFFICULTY_DESCRIPTIONS.get(InterviewDefaults.DIFFICULTY)
        );
    }

    /**
     * 根据配置的比例计算简历题目的数量
     */
    private int calculateResumeQuestionCount(int questionCount, int resumeRatio) {
        if (questionCount <= 0) {
            return 0;
        }
        int resumeCount = (int) Math.round(questionCount * (resumeRatio / 100.0));
        return Math.max(1, Math.min(questionCount, resumeCount));
    }

    /**
     * 将AI返回的DTO转换为InterviewQuestionDTO列表
     * 同时展开追问为独立的题目记录
     */
    private List<InterviewQuestionDTO> convertToQuestions(QuestionListDTO dto) {
        List<InterviewQuestionDTO> questions = new ArrayList<>();
        int index = 0;
        if (dto == null || dto.questions() == null) {
            return questions;
        }

        for (QuestionDTO item : dto.questions()) {
            // 跳过无效题目
            if (item == null || item.question() == null || item.question().isBlank()) {
                continue;
            }
            // 标准化题目类型
            String type = item.type() != null && !item.type().isBlank()
                    ? item.type().toUpperCase()
                    : DEFAULT_QUESTION_TYPE;
            int mainIndex = index;
            // 添加主问题
            questions.add(InterviewQuestionDTO.create(
                    index++,
                    item.question().trim(),
                    type,
                    item.category(),
                    item.topicSummary(),
                    false,
                    null
            ));

            // 清洗追问列表并按配置数量限制添加
            List<String> followUps = sanitizeFollowUps(item.followUps());
            for (int i = 0; i < followUps.size(); i++) {
                questions.add(InterviewQuestionDTO.create(
                        index++,
                        followUps.get(i),
                        type,
                        buildFollowUpCategory(item.category(), i + 1),
                        null,
                        true,
                        mainIndex
                ));
            }
        }
        return questions;
    }

    /**
     * 按最大主问题数量截断题目列表
     */
    private List<InterviewQuestionDTO> capToMainCount(
            List<InterviewQuestionDTO> questions,
            int maxMainCount
    ) {
        long currentMainCount = questions.stream().filter(question -> !question.isFollowUp()).count();
        if (currentMainCount <= maxMainCount) {
            if (currentMainCount < maxMainCount) {
                log.warn("AI生成的主问题数量少于预期: 期望={}, 实际={}", maxMainCount, currentMainCount);
            }
            return questions;
        }

        // 超过时截断多余的主问题及其追问
        List<InterviewQuestionDTO> capped = new ArrayList<>();
        int mainSeen = 0;
        for (InterviewQuestionDTO question : questions) {
            if (!question.isFollowUp()) {
                mainSeen++;
            }
            if (mainSeen > maxMainCount) {
                break;
            }
            capped.add(question);
        }
        return capped;
    }

    /**
     * 生成兜底题目
     * 当AI生成失败时，根据方向分类或通用兜底列表生成题目
     */
    private List<InterviewQuestionDTO> generateFallbackQuestions(SkillDTO skill, int count) {
        List<SkillCategoryDTO> categories = skill != null ? skill.categories() : List.of();
        List<InterviewQuestionDTO> questions = new ArrayList<>();
        int index = 0;

        // 有方向分类时，按分类循环生成题目
        if (!categories.isEmpty()) {
            int generated = 0;
            while (generated < count) {
                SkillCategoryDTO category = categories.get(generated % categories.size());
                String question = "请谈谈你对" + category.label() + "的理解和实战经验。";
                questions.add(InterviewQuestionDTO.create(
                        index++,
                        question,
                        category.key(),
                        category.label(),
                        null,
                        false,
                        null
                ));
                int mainIndex = index - 1;
                // 添加默认追问
                for (int order = 0; order < followUpCount; order++) {
                    questions.add(InterviewQuestionDTO.create(
                            index++,
                            buildDefaultFollowUp(question, order + 1),
                            category.key(),
                            buildFollowUpCategory(category.label(), order + 1),
                            null,
                            true,
                            mainIndex
                    ));
                }
                generated++;
            }
            return questions;
        }

        // 无方向分类时使用通用兜底题目
        for (int i = 0; i < Math.min(count, GENERIC_FALLBACK_QUESTIONS.length); i++) {
            String[] fallback = GENERIC_FALLBACK_QUESTIONS[i];
            questions.add(InterviewQuestionDTO.create(
                    index++,
                    fallback[0],
                    fallback[1],
                    fallback[2],
                    null,
                    false,
                    null
            ));
            int mainIndex = index - 1;
            // 添加默认追问
            for (int order = 0; order < followUpCount; order++) {
                questions.add(InterviewQuestionDTO.create(
                        index++,
                        buildDefaultFollowUp(fallback[0], order + 1),
                        fallback[1],
                        buildFollowUpCategory(fallback[2], order + 1),
                        null,
                        true,
                        mainIndex
                ));
            }
        }
        return questions;
    }

    /**
     * 构建历史问题文本
     * 将已问过的问题按类型分组，用于提示AI避免重复出题
     */
    private String buildHistoricalSection(List<HistoricalQuestion> historicalQuestions) {
        if (historicalQuestions == null || historicalQuestions.isEmpty()) {
            return "暂无历史问题。";
        }
        // 按题目类型分组
        Map<String, List<String>> grouped = new HashMap<>();
        for (HistoricalQuestion historicalQuestion : historicalQuestions) {
            String type = historicalQuestion.type() != null && !historicalQuestion.type().isBlank()
                    ? historicalQuestion.type()
                    : DEFAULT_QUESTION_TYPE;
            String summary = historicalQuestion.topicSummary();
            // 无主题摘要时截取问题文本前30个字符作为摘要
            if (summary == null || summary.isBlank()) {
                String question = historicalQuestion.question();
                summary = question != null && question.length() > 30
                        ? question.substring(0, 30) + "..."
                        : question;
            }
            grouped.computeIfAbsent(type, key -> new ArrayList<>()).add(summary);
        }

        // 构建分组文本
        StringBuilder builder = new StringBuilder("已覆盖的题目主题，请避免重复：\n");
        for (Map.Entry<String, List<String>> entry : grouped.entrySet()) {
            builder.append("- ").append(entry.getKey()).append(": ");
            builder.append(String.join(", ", entry.getValue()));
            builder.append('\n');
        }
        return builder.toString();
    }

    /**
     * 构建JD（职位描述）文本段落
     */
    private String buildJdSection(String sourceJd) {
        if (sourceJd == null || sourceJd.isBlank()) {
            return "";
        }
        return PromptSecurityConstants.DATA_BOUNDARY_INSTRUCTION + "\n"
                + "职位描述：\n"
                + promptSanitizer.wrapWithDelimiters("jd", promptSanitizer.sanitize(sourceJd));
    }

    /**
     * 构建方向人设提示词段落
     */
    private String buildSkillPersonaSection(SkillDTO skill) {
        if (skill == null || skill.persona() == null || skill.persona().isBlank()) {
            return "";
        }
        return "\n\n# 方向人设\n"
                + promptSanitizer.wrapWithDelimiters("方向人设", skill.persona());
    }

    /**
     * 清洗追问列表，过滤空值并按配置数量限制
     */
    private List<String> sanitizeFollowUps(List<String> followUps) {
        if (followUpCount == 0 || followUps == null || followUps.isEmpty()) {
            return List.of();
        }
        return followUps.stream()
                .filter(item -> item != null && !item.isBlank())
                .map(String::trim)
                .limit(followUpCount)
                .collect(Collectors.toList());
    }

    /**
     * 构建默认追问列表
     */
    private List<String> buildDefaultFollowUps(String mainQuestion) {
        List<String> defaults = new ArrayList<>();
        for (int order = 1; order <= followUpCount; order++) {
            defaults.add(buildDefaultFollowUp(mainQuestion, order));
        }
        return defaults;
    }

    /**
     * 构建追问的分类名称
     * 格式为"原分类 追问N"
     */
    private String buildFollowUpCategory(String category, int order) {
        if (category == null || category.isBlank()) {
            return "追问" + order;
        }
        String normalizedCategory = InterviewQuestionDTO.normalizeCategory(category);
        // 移除已有的追问后缀再重新添加
        String baseCategory = normalizedCategory.replaceFirst("\\s*追问\\d+\\s*$", "").trim();
        if (baseCategory.isBlank()) {
            return "追问" + order;
        }
        return baseCategory + " 追问" + order;
    }

    /**
     * 构建默认追问文本
     */
    private String buildDefaultFollowUp(String mainQuestion, int order) {
        if (order == 1) {
            return "针对「" + mainQuestion + "」，请结合实际项目案例进行说明。";
        }
        return "针对「" + mainQuestion + "」，在生产环境中你会如何排查和解决相关问题？";
    }

    /**
     * 构建OpenAI聊天选项，设置温度参数
     */
    private OpenAiChatOptions chatOptions(double temperature) {
        return OpenAiChatOptions.builder()
                .temperature(temperature)
                .build();
    }
}