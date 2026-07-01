package interview.guide.common.evaluation;

import interview.guide.common.ai.StructuredOutputInvoker;
import interview.guide.common.evaluation.EvaluationReport.QuestionEvaluation;
import interview.guide.common.evaluation.EvaluationReport.ReferenceAnswer;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.aisettings.service.SystemAiSettingsResolver;
import interview.guide.modules.aisettings.service.SystemAiSettingsResolver.InterviewSnapshot;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

@Service
public class UnifiedEvaluationService {

    private static final Logger log = LoggerFactory.getLogger(UnifiedEvaluationService.class);
    private static final int MAX_REFERENCE_CONTEXT_CHARS = 6000;

    private final PromptTemplate systemPromptTemplate;
    private final PromptTemplate userPromptTemplate;
    private final BeanOutputConverter<BatchReportDTO> outputConverter;
    private final PromptTemplate summarySystemPromptTemplate;
    private final PromptTemplate summaryUserPromptTemplate;
    private final BeanOutputConverter<SummaryDTO> summaryOutputConverter;
    private final StructuredOutputInvoker structuredOutputInvoker;
    private final SystemAiSettingsResolver systemAiSettingsResolver;
    private final int evaluationBatchSize;

    private record QuestionEvalDTO(
            int questionIndex,
            int score,
            String feedback,
            String referenceAnswer,
            List<String> keyPoints
    ) {
    }

    private record BatchReportDTO(
            int overallScore,
            String overallFeedback,
            List<String> strengths,
            List<String> improvements,
            List<QuestionEvalDTO> questionEvaluations
    ) {
    }

    private record BatchResult(
            int startIndex,
            int endIndex,
            BatchReportDTO report
    ) {
    }

    private record SummaryDTO(
            String overallFeedback,
            List<String> strengths,
            List<String> improvements
    ) {
    }

    public UnifiedEvaluationService(
            StructuredOutputInvoker structuredOutputInvoker,
            SystemAiSettingsResolver systemAiSettingsResolver,
            ResourceLoader resourceLoader,
            InterviewEvaluationProperties evaluationProperties
    ) throws IOException {
        this.structuredOutputInvoker = structuredOutputInvoker;
        this.systemAiSettingsResolver = systemAiSettingsResolver;
        this.systemPromptTemplate = new PromptTemplate(loadPrompt(
                resourceLoader,
                evaluationProperties.getSystemPromptPath()
        ));
        this.userPromptTemplate = new PromptTemplate(loadPrompt(
                resourceLoader,
                evaluationProperties.getUserPromptPath()
        ));
        this.outputConverter = new BeanOutputConverter<>(BatchReportDTO.class);
        this.summarySystemPromptTemplate = new PromptTemplate(loadPrompt(
                resourceLoader,
                evaluationProperties.getSummarySystemPromptPath()
        ));
        this.summaryUserPromptTemplate = new PromptTemplate(loadPrompt(
                resourceLoader,
                evaluationProperties.getSummaryUserPromptPath()
        ));
        this.summaryOutputConverter = new BeanOutputConverter<>(SummaryDTO.class);
        this.evaluationBatchSize = Math.max(1, evaluationProperties.getBatchSize());
    }

    private String loadPrompt(ResourceLoader resourceLoader, String path) throws IOException {
        Resource resource = resourceLoader.getResource(path);
        return resource.getContentAsString(StandardCharsets.UTF_8);
    }

    public EvaluationReport evaluate(
            ChatClient chatClient,
            String sessionId,
            List<QaRecord> qaRecords,
            String resumeText
    ) {
        return evaluate(chatClient, sessionId, qaRecords, resumeText, null);
    }

    public EvaluationReport evaluate(
            ChatClient chatClient,
            String sessionId,
            List<QaRecord> qaRecords,
            String resumeText,
            String referenceContext
    ) {
        log.info("Interview evaluation started: sessionId={}, questions={}", sessionId, qaRecords.size());
        InterviewSnapshot settings = systemAiSettingsResolver.resolve().interview();
        String resumeContext = truncate(resumeText != null ? resumeText : "", 3000);
        String referenceBaseline = truncate(referenceContext != null ? referenceContext.trim() : "",
                MAX_REFERENCE_CONTEXT_CHARS);

        List<BatchResult> batchResults = evaluateInBatches(
                chatClient,
                sessionId,
                resumeContext,
                qaRecords,
                referenceBaseline,
                settings
        );
        List<QuestionEvalDTO> mergedEvaluations = mergeQuestionEvaluations(batchResults);
        String fallbackFeedback = mergeOverallFeedback(batchResults);
        List<String> fallbackStrengths = mergeListItems(batchResults, true);
        List<String> fallbackImprovements = mergeListItems(batchResults, false);

        SummaryDTO summary = summarizeBatchResults(
                chatClient,
                sessionId,
                resumeContext,
                referenceBaseline,
                qaRecords,
                mergedEvaluations,
                fallbackFeedback,
                fallbackStrengths,
                fallbackImprovements,
                settings
        );

        return buildReport(
                sessionId,
                qaRecords,
                mergedEvaluations,
                summary.overallFeedback(),
                summary.strengths(),
                summary.improvements()
        );
    }

    private List<BatchResult> evaluateInBatches(
            ChatClient chatClient,
            String sessionId,
            String resumeContext,
            List<QaRecord> qaRecords,
            String referenceContext,
            InterviewSnapshot settings
    ) {
        List<BatchResult> results = new ArrayList<>();
        for (int start = 0; start < qaRecords.size(); start += evaluationBatchSize) {
            int end = Math.min(start + evaluationBatchSize, qaRecords.size());
            List<QaRecord> batch = qaRecords.subList(start, end);
            BatchReportDTO report = evaluateBatch(
                    chatClient,
                    sessionId,
                    resumeContext,
                    referenceContext,
                    batch,
                    settings
            );
            results.add(new BatchResult(start, end, report));
        }
        return results;
    }

    private BatchReportDTO evaluateBatch(
            ChatClient chatClient,
            String sessionId,
            String resumeContext,
            String referenceContext,
            List<QaRecord> batch,
            InterviewSnapshot settings
    ) {
        String systemPromptWithFormat =
                systemPromptTemplate.render() + "\n\n" + outputConverter.getFormat();
        Map<String, Object> variables = new HashMap<>();
        variables.put("resumeText", resumeContext);
        variables.put("qaRecords", buildQARecords(batch));
        variables.put("referenceContext", referenceContext != null && !referenceContext.isBlank()
                ? referenceContext
                : "None");
        String userPrompt = userPromptTemplate.render(variables);

        try {
            return structuredOutputInvoker.invoke(
                    chatClient,
                    systemPromptWithFormat,
                    userPrompt,
                    outputConverter,
                    chatOptions(settings.scoringTemperature()),
                    ErrorCode.INTERVIEW_EVALUATION_FAILED,
                    "Batch evaluation failed: ",
                    "batch_evaluation",
                    log
            );
        } catch (Exception e) {
            log.error("Batch evaluation failed: sessionId={}, batchSize={}",
                    sessionId, batch.size(), e);
            return null;
        }
    }

    private SummaryDTO summarizeBatchResults(
            ChatClient chatClient,
            String sessionId,
            String resumeContext,
            String referenceContext,
            List<QaRecord> qaRecords,
            List<QuestionEvalDTO> evaluations,
            String fallbackFeedback,
            List<String> fallbackStrengths,
            List<String> fallbackImprovements,
            InterviewSnapshot settings
    ) {
        try {
            String systemWithFormat =
                    summarySystemPromptTemplate.render() + "\n\n" + summaryOutputConverter.getFormat();
            Map<String, Object> variables = new HashMap<>();
            variables.put("resumeText", resumeContext);
            variables.put("referenceContext", referenceContext != null && !referenceContext.isBlank()
                    ? referenceContext
                    : "None");
            variables.put("questionHighlights", buildQuestionHighlights(qaRecords, evaluations));
            variables.put("fallbackOverallFeedback", fallbackFeedback);
            variables.put("fallbackStrengths", String.join("\n", fallbackStrengths));
            variables.put("fallbackImprovements", String.join("\n", fallbackImprovements));
            String summaryUser = summaryUserPromptTemplate.render(variables);

            SummaryDTO dto = structuredOutputInvoker.invoke(
                    chatClient,
                    systemWithFormat,
                    summaryUser,
                    summaryOutputConverter,
                    chatOptions(settings.commentTemperature()),
                    ErrorCode.INTERVIEW_EVALUATION_FAILED,
                    "Summary evaluation failed: ",
                    "summary_evaluation",
                    log
            );

            String feedback = dto != null
                    && dto.overallFeedback() != null
                    && !dto.overallFeedback().isBlank()
                    ? dto.overallFeedback()
                    : fallbackFeedback;
            return new SummaryDTO(
                    feedback,
                    sanitizeItems(dto != null ? dto.strengths() : null, fallbackStrengths),
                    sanitizeItems(dto != null ? dto.improvements() : null, fallbackImprovements)
            );
        } catch (Exception e) {
            log.warn("Summary evaluation failed; using batch summary: sessionId={}, error={}",
                    sessionId, e.getMessage());
            return new SummaryDTO(fallbackFeedback, fallbackStrengths, fallbackImprovements);
        }
    }

    private String buildQARecords(List<QaRecord> batch) {
        StringBuilder builder = new StringBuilder();
        for (QaRecord record : batch) {
            builder.append("Question ")
                    .append(record.questionIndex() + 1)
                    .append(" [")
                    .append(record.category())
                    .append("]: ")
                    .append(record.question())
                    .append('\n');
            builder.append("Answer: ")
                    .append(record.userAnswer() != null ? record.userAnswer() : "(unanswered)")
                    .append("\n\n");
        }
        return builder.toString();
    }

    private List<QuestionEvalDTO> mergeQuestionEvaluations(List<BatchResult> batchResults) {
        List<QuestionEvalDTO> merged = new ArrayList<>();
        for (BatchResult result : batchResults) {
            int expectedSize = result.endIndex() - result.startIndex();
            List<QuestionEvalDTO> current = result.report() != null
                    && result.report().questionEvaluations() != null
                    ? result.report().questionEvaluations()
                    : List.of();
            for (int i = 0; i < expectedSize; i++) {
                if (i < current.size() && current.get(i) != null) {
                    merged.add(current.get(i));
                } else {
                    merged.add(new QuestionEvalDTO(
                            result.startIndex() + i,
                            0,
                            "No valid evaluation was generated for this question.",
                            "",
                            List.of()
                    ));
                }
            }
        }
        return merged;
    }

    private String mergeOverallFeedback(List<BatchResult> batchResults) {
        String feedback = batchResults.stream()
                .map(BatchResult::report)
                .filter(report -> report != null
                        && report.overallFeedback() != null
                        && !report.overallFeedback().isBlank())
                .map(BatchReportDTO::overallFeedback)
                .collect(Collectors.joining("\n\n"));
        return feedback.isBlank()
                ? "The interview has been evaluated in batches, but no valid overall comment was generated."
                : feedback;
    }

    private List<String> mergeListItems(List<BatchResult> batchResults, boolean strengthsMode) {
        Set<String> merged = new LinkedHashSet<>();
        for (BatchResult result : batchResults) {
            BatchReportDTO report = result.report();
            if (report == null) {
                continue;
            }
            List<String> items = strengthsMode ? report.strengths() : report.improvements();
            if (items == null) {
                continue;
            }
            items.stream()
                    .filter(item -> item != null && !item.isBlank())
                    .map(String::trim)
                    .forEach(merged::add);
        }
        return merged.stream().limit(8).toList();
    }

    private List<String> sanitizeItems(List<String> primary, List<String> fallback) {
        List<String> source = primary != null && !primary.isEmpty() ? primary : fallback;
        if (source == null || source.isEmpty()) {
            return List.of();
        }
        return source.stream()
                .filter(item -> item != null && !item.isBlank())
                .map(String::trim)
                .distinct()
                .limit(8)
                .toList();
    }

    private EvaluationReport buildReport(
            String sessionId,
            List<QaRecord> qaRecords,
            List<QuestionEvalDTO> evaluations,
            String overallFeedback,
            List<String> strengths,
            List<String> improvements
    ) {
        List<QuestionEvaluation> questionDetails = new ArrayList<>();
        List<ReferenceAnswer> referenceAnswers = new ArrayList<>();

        long answeredCount = qaRecords.stream()
                .filter(record -> record.userAnswer() != null && !record.userAnswer().isBlank())
                .count();
        int evalSize = evaluations != null ? evaluations.size() : 0;

        for (int i = 0; i < qaRecords.size(); i++) {
            QaRecord record = qaRecords.get(i);
            QuestionEvalDTO evaluation = i < evalSize ? evaluations.get(i) : null;
            boolean hasAnswer = record.userAnswer() != null && !record.userAnswer().isBlank();
            int score = hasAnswer && evaluation != null ? evaluation.score() : 0;
            String feedback = evaluation != null && evaluation.feedback() != null
                    ? evaluation.feedback()
                    : "No evaluation feedback was generated for this question.";
            String referenceAnswer = evaluation != null && evaluation.referenceAnswer() != null
                    ? evaluation.referenceAnswer()
                    : "";
            List<String> keyPoints = evaluation != null && evaluation.keyPoints() != null
                    ? evaluation.keyPoints()
                    : List.of();

            questionDetails.add(new QuestionEvaluation(
                    record.questionIndex(),
                    record.question(),
                    record.category(),
                    record.userAnswer(),
                    score,
                    feedback
            ));
            referenceAnswers.add(new ReferenceAnswer(
                    record.questionIndex(),
                    record.question(),
                    referenceAnswer,
                    keyPoints
            ));
        }

        int overallScore = answeredCount == 0
                ? 0
                : (int) questionDetails.stream().mapToInt(QuestionEvaluation::score).average().orElse(0);

        return new EvaluationReport(
                sessionId,
                qaRecords.size(),
                overallScore,
                questionDetails,
                overallFeedback,
                strengths != null ? strengths : List.of(),
                improvements != null ? improvements : List.of(),
                referenceAnswers
        );
    }

    private String buildQuestionHighlights(
            List<QaRecord> qaRecords,
            List<QuestionEvalDTO> evaluations
    ) {
        List<String> highlights = new ArrayList<>();
        for (int i = 0; i < qaRecords.size(); i++) {
            QaRecord record = qaRecords.get(i);
            QuestionEvalDTO evaluation = i < evaluations.size() ? evaluations.get(i) : null;
            int score = evaluation != null ? evaluation.score() : 0;
            String feedback = evaluation != null && evaluation.feedback() != null
                    ? evaluation.feedback()
                    : "";
            highlights.add(String.format(
                    "- Q%d | %s | score:%d | feedback:%s",
                    record.questionIndex() + 1,
                    truncate(record.question(), 50),
                    score,
                    truncate(feedback, 80)
            ));
        }
        return highlights.stream().limit(20).collect(Collectors.joining("\n"));
    }

    private String truncate(String text, int maxChars) {
        if (text == null || text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, maxChars) + "\n...(truncated)";
    }

    private OpenAiChatOptions chatOptions(double temperature) {
        return OpenAiChatOptions.builder()
                .temperature(temperature)
                .build();
    }
}
