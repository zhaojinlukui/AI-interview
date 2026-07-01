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

@Service
public class InterviewQuestionService {

    private static final Logger log = LoggerFactory.getLogger(InterviewQuestionService.class);

    private static final String DEFAULT_QUESTION_TYPE = "GENERAL";
    private static final int MAX_FOLLOW_UP_COUNT = 2;
    private static final String GENERIC_MODE_SYSTEM_APPEND = """
        \n\n# 通用面试模式
        本次面试无候选人简历，请出该方向的标准面试题。
        - 禁止出现"你在简历中提到..."、"你在项目中..."等暗示存在简历的表述
        - 问题表述应与简历无关，直接考察该方向的技术能力
        """;

    private static final Map<String, String> DIFFICULTY_DESCRIPTIONS = Map.of(
            "junior", "校招/0-1年经验。考察基础概念和简单应用。",
            "mid", "1-3年经验。考察原理理解和实战经验。",
            "senior", "3年+经验。考察架构设计和深度调优。"
    );
    private static final String[][] GENERIC_FALLBACK_QUESTIONS = {
            {"请描述一个你主导解决的技术难题，你的分析思路是什么？", "GENERAL", "综合能力"},
            {"你在做技术方案选型时，通常考虑哪些因素？请举例说明。", "GENERAL", "综合能力"},
            {"请分享一次你处理线上故障的经历，从发现到修复的完整过程。", "GENERAL", "综合能力"},
            {"你如何保证代码质量？介绍你实践过的有效手段。", "GENERAL", "综合能力"},
            {"描述一个你做过的技术优化案例，优化的动机、方案和效果。", "GENERAL", "综合能力"},
            {"你在团队协作中遇到过最大的分歧是什么？如何解决的？", "GENERAL", "综合能力"},
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

    private record QuestionListDTO(List<QuestionDTO> questions) {
    }

    private record QuestionDTO(
            String question,
            String type,
            String category,
            String topicSummary,
            List<String> followUps
    ) {
    }

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

    private static PromptTemplate loadTemplate(ResourceLoader loader, String location)
            throws IOException {
        return new PromptTemplate(
                loader.getResource(location).getContentAsString(StandardCharsets.UTF_8)
        );
    }

    @PreDestroy
    void destroy() {
        questionExecutor.shutdownNow();
    }

    public List<InterviewQuestionDTO> generateQuestionsBySkill(
            String skillId,
            String difficulty,
            String resumeText,
            int questionCount,
            List<HistoricalQuestion> historicalQuestions,
            List<CategoryDTO> customCategories,
            String jdText
    ) {
        SkillDTO skill = resolveSkill(skillId, customCategories, jdText);
        String difficultyDesc = resolveDifficulty(difficulty);
        ChatClient questionChatClient = aiClientFactory.getPlainChatClient();
        InterviewSnapshot settings = systemAiSettingsResolver.resolve().interview();
        boolean hasResume = resumeText != null && !resumeText.isBlank();
        String historicalSection = buildHistoricalSection(historicalQuestions);
        int mainQuestionCount = calculateMainQuestionCount(questionCount);

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

        int resumeCount = calculateResumeQuestionCount(
                mainQuestionCount,
                settings.resumeQuestionRatio()
        );
        int directionCount = mainQuestionCount - resumeCount;
        log.info(
                "Interview question split: skillId={}, total={}, mainBudget={}, "
                        + "resumeCount={}, directionCount={}",
                skillId,
                questionCount,
                mainQuestionCount,
                resumeCount,
                directionCount
        );

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
            resumeQuestions = resumeFuture.join();
        } catch (CompletionException e) {
            log.error("Resume-based question generation failed; falling back to direction questions",
                    e.getCause());
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
            directionQuestions = directionFuture.join();
        } catch (CompletionException e) {
            log.error("Direction question generation failed; using resume questions only", e.getCause());
            if (resumeQuestions.isEmpty()) {
                return finalizeGeneratedQuestions(
                        skill,
                        generateFallbackQuestions(skill, mainQuestionCount),
                        mainQuestionCount,
                        questionCount
                );
            }
            return finalizeGeneratedQuestions(skill, resumeQuestions, mainQuestionCount, questionCount);
        }

        if (resumeQuestions.isEmpty() && directionQuestions.isEmpty()) {
            return finalizeGeneratedQuestions(
                    skill,
                    generateFallbackQuestions(skill, mainQuestionCount),
                    mainQuestionCount,
                    questionCount
            );
        }

        List<InterviewQuestionDTO> merged = mergeQuestionBatches(resumeQuestions, directionQuestions);
        return finalizeGeneratedQuestions(skill, merged, mainQuestionCount, questionCount);
    }

    private int calculateMainQuestionCount(int totalQuestionCount) {
        if (totalQuestionCount <= 0) {
            return 0;
        }
        if (followUpCount <= 0) {
            return totalQuestionCount;
        }
        int questionGroupSize = followUpCount + 1;
        return Math.max(1, (int) Math.ceil(totalQuestionCount / (double) questionGroupSize));
    }

    private List<InterviewQuestionDTO> finalizeGeneratedQuestions(
            SkillDTO skill,
            List<InterviewQuestionDTO> questions,
            int expectedMainCount,
            int requestedTotalCount
    ) {
        List<InterviewQuestionDTO> safeQuestions = questions != null ? questions : List.of();
        List<InterviewQuestionDTO> completedQuestions = fillMissingMainQuestions(
                skill,
                safeQuestions,
                expectedMainCount
        );
        return capToTotalCount(
                normalizeQuestionsWithFollowUps(completedQuestions),
                requestedTotalCount
        );
    }

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
        Map<String, Object> variables = new HashMap<>();
        variables.put("questionCount", questionCount);
        variables.put("followUpCount", followUpCount);
        variables.put("skillName", skill.name());
        variables.put("skillDescription", skill.description() != null ? skill.description() : "");
        variables.put("difficultyDescription", difficultyDesc);
        variables.put("resumeText", resumeText);
        variables.put("historicalSection", historicalSection);

        String systemPrompt = resumeSystemPromptTemplate.render()
                + buildSkillPersonaSection(skill)
                + "\n\n" + outputConverter.getFormat();
        String userPrompt = resumeUserPromptTemplate.render(variables);

        QuestionListDTO dto = structuredOutputInvoker.invoke(
                questionClient,
                systemPrompt,
                userPrompt,
                outputConverter,
                chatOptions(settings.questionTemperature()),
                ErrorCode.INTERVIEW_QUESTION_GENERATION_FAILED,
                "Resume question generation failed: ",
                "resume_questions",
                log
        );

        return capToMainCount(convertToQuestions(dto), questionCount);
    }

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
        Map<String, Integer> allocation = skillService.calculateAllocation(
                skill.categories(),
                questionCount
        );
        String allocationTable = skillService.buildAllocationDescription(
                allocation,
                skill.categories()
        );

        try {
            String systemPrompt = skillSystemPromptTemplate.render()
                    + buildSkillPersonaSection(skill)
                    + GENERIC_MODE_SYSTEM_APPEND
                    + outputConverter.getFormat();

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

            QuestionListDTO dto = structuredOutputInvoker.invoke(
                    questionClient,
                    systemPrompt,
                    userPrompt,
                    outputConverter,
                    chatOptions(settings.questionTemperature()),
                    ErrorCode.INTERVIEW_QUESTION_GENERATION_FAILED,
                    "Direction question generation failed: ",
                    "direction_questions",
                    log
            );

            List<InterviewQuestionDTO> questions = capToMainCount(convertToQuestions(dto), questionCount);
            if (questions.stream().noneMatch(question -> !question.isFollowUp())) {
                return generateFallbackQuestions(skill, questionCount);
            }
            return questions;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("Direction question generation failed; using fallback questions", e);
            return generateFallbackQuestions(skill, questionCount);
        }
    }

    private List<InterviewQuestionDTO> normalizeQuestionsWithFollowUps(
            List<InterviewQuestionDTO> mainQuestions
    ) {
        if (mainQuestions == null || mainQuestions.isEmpty()) {
            return List.of();
        }
        if (followUpCount == 0) {
            return reindexQuestions(mainQuestions);
        }

        List<InterviewQuestionDTO> mains = mainQuestions.stream()
                .filter(question -> !question.isFollowUp())
                .toList();

        List<InterviewQuestionDTO> result = new ArrayList<>();
        int index = 0;
        for (InterviewQuestionDTO main : mains) {
            int mainIndex = index;
            result.add(InterviewQuestionDTO.create(
                    index++,
                    main.question(),
                    main.type(),
                    main.category(),
                    main.topicSummary(),
                    false,
                    null
            ));

            List<String> followUps = extractGeneratedFollowUps(mainQuestions, main);
            if (followUps.size() < followUpCount) {
                followUps = fillMissingFollowUps(main.question(), followUps);
            }
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
        List<InterviewQuestionDTO> fallbackQuestions = generateFallbackQuestions(
                skill,
                expectedMainCount - (int) currentMainCount
        );
        filled.addAll(fallbackQuestions);
        return filled;
    }

    private List<InterviewQuestionDTO> reindexQuestions(List<InterviewQuestionDTO> questions) {
        List<InterviewQuestionDTO> reindexed = new ArrayList<>();
        int index = 0;
        for (InterviewQuestionDTO question : questions) {
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

    private List<String> fillMissingFollowUps(String mainQuestion, List<String> generatedFollowUps) {
        List<String> followUps = new ArrayList<>(
                generatedFollowUps != null ? generatedFollowUps : List.of()
        );
        for (int order = followUps.size() + 1; order <= followUpCount; order++) {
            followUps.add(buildDefaultFollowUp(mainQuestion, order));
        }
        return followUps;
    }

    private SkillDTO resolveSkill(String skillId, List<CategoryDTO> customCategories, String jdText) {
        if (InterviewSkillService.CUSTOM_SKILL_ID.equals(skillId)
                && customCategories != null
                && !customCategories.isEmpty()) {
            return skillService.buildCustomSkill(customCategories, jdText != null ? jdText : "");
        }
        return skillService.getSkill(skillId);
    }

    private String resolveDifficulty(String difficulty) {
        return DIFFICULTY_DESCRIPTIONS.getOrDefault(
                difficulty != null ? difficulty : InterviewDefaults.DIFFICULTY,
                DIFFICULTY_DESCRIPTIONS.get(InterviewDefaults.DIFFICULTY)
        );
    }

    private int calculateResumeQuestionCount(int questionCount, int resumeRatio) {
        if (questionCount <= 0) {
            return 0;
        }
        int resumeCount = (int) Math.round(questionCount * (resumeRatio / 100.0));
        return Math.max(1, Math.min(questionCount, resumeCount));
    }

    private List<InterviewQuestionDTO> convertToQuestions(QuestionListDTO dto) {
        List<InterviewQuestionDTO> questions = new ArrayList<>();
        int index = 0;
        if (dto == null || dto.questions() == null) {
            return questions;
        }

        for (QuestionDTO item : dto.questions()) {
            if (item == null || item.question() == null || item.question().isBlank()) {
                continue;
            }
            String type = item.type() != null && !item.type().isBlank()
                    ? item.type().toUpperCase()
                    : DEFAULT_QUESTION_TYPE;
            int mainIndex = index;
            questions.add(InterviewQuestionDTO.create(
                    index++,
                    item.question().trim(),
                    type,
                    item.category(),
                    item.topicSummary(),
                    false,
                    null
            ));

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

    private List<InterviewQuestionDTO> capToMainCount(
            List<InterviewQuestionDTO> questions,
            int maxMainCount
    ) {
        long currentMainCount = questions.stream().filter(question -> !question.isFollowUp()).count();
        if (currentMainCount <= maxMainCount) {
            if (currentMainCount < maxMainCount) {
                log.warn("AI generated fewer main questions than requested: requested={}, actual={}",
                        maxMainCount, currentMainCount);
            }
            return questions;
        }

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

    private List<InterviewQuestionDTO> generateFallbackQuestions(SkillDTO skill, int count) {
        List<SkillCategoryDTO> categories = skill != null ? skill.categories() : List.of();
        List<InterviewQuestionDTO> questions = new ArrayList<>();
        int index = 0;

        if (!categories.isEmpty()) {
            int generated = 0;
            while (generated < count) {
                SkillCategoryDTO category = categories.get(generated % categories.size());
                String question = "Please discuss your understanding and hands-on experience in "
                        + category.label() + ".";
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

    private String buildHistoricalSection(List<HistoricalQuestion> historicalQuestions) {
        if (historicalQuestions == null || historicalQuestions.isEmpty()) {
            return "No historical questions.";
        }
        Map<String, List<String>> grouped = new HashMap<>();
        for (HistoricalQuestion historicalQuestion : historicalQuestions) {
            String type = historicalQuestion.type() != null && !historicalQuestion.type().isBlank()
                    ? historicalQuestion.type()
                    : DEFAULT_QUESTION_TYPE;
            String summary = historicalQuestion.topicSummary();
            if (summary == null || summary.isBlank()) {
                String question = historicalQuestion.question();
                summary = question != null && question.length() > 30
                        ? question.substring(0, 30) + "..."
                        : question;
            }
            grouped.computeIfAbsent(type, key -> new ArrayList<>()).add(summary);
        }

        StringBuilder builder = new StringBuilder("Already covered topics. Avoid duplicates:\n");
        for (Map.Entry<String, List<String>> entry : grouped.entrySet()) {
            builder.append("- ").append(entry.getKey()).append(": ");
            builder.append(String.join(", ", entry.getValue()));
            builder.append('\n');
        }
        return builder.toString();
    }

    private String buildJdSection(String sourceJd) {
        if (sourceJd == null || sourceJd.isBlank()) {
            return "";
        }
        return PromptSecurityConstants.DATA_BOUNDARY_INSTRUCTION + "\n"
                + "Job description:\n"
                + promptSanitizer.wrapWithDelimiters("jd", promptSanitizer.sanitize(sourceJd));
    }

    private String buildSkillPersonaSection(SkillDTO skill) {
        if (skill == null || skill.persona() == null || skill.persona().isBlank()) {
            return "";
        }
        return "\n\n# Skill Persona\n"
                + promptSanitizer.wrapWithDelimiters("skill_persona", skill.persona());
    }

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

    private List<String> buildDefaultFollowUps(String mainQuestion) {
        List<String> defaults = new ArrayList<>();
        for (int order = 1; order <= followUpCount; order++) {
            defaults.add(buildDefaultFollowUp(mainQuestion, order));
        }
        return defaults;
    }

    private String buildFollowUpCategory(String category, int order) {
        if (category == null || category.isBlank()) {
            return "追问" + order;
        }
        String normalizedCategory = InterviewQuestionDTO.normalizeCategory(category);
        String baseCategory = normalizedCategory.replaceFirst("\\s*追问\\d+\\s*$", "").trim();
        if (baseCategory.isBlank()) {
            return "追问" + order;
        }
        return baseCategory + " 追问" + order;
    }

    private String buildDefaultFollowUp(String mainQuestion, int order) {
        if (order == 1) {
            return "Based on \"" + mainQuestion + "\", please explain it with a real project example.";
        }
        return "Based on \"" + mainQuestion + "\", how would you troubleshoot it in production?";
    }

    private OpenAiChatOptions chatOptions(double temperature) {
        return OpenAiChatOptions.builder()
                .temperature(temperature)
                .build();
    }
}
