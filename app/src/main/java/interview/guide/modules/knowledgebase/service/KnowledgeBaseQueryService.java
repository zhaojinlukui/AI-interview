package interview.guide.modules.knowledgebase.service;

import interview.guide.common.ai.AiClientFactory;
import interview.guide.common.ai.PromptSecurityConstants;
import interview.guide.modules.aisettings.service.SystemAiSettingsResolver;
import interview.guide.modules.aisettings.service.SystemAiSettingsResolver.RagSearchSnapshot;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

@Slf4j
@Service
public class KnowledgeBaseQueryService {

    private static final String NO_RESULT_RESPONSE = "抱歉，在选定的知识库中未检索到相关信息。" + "请换一个更具体的关键词或补充上下文后再试。";
    private static final int STREAM_PROBE_CHARS = 120;
    private static final int MAX_REWRITE_HISTORY_CHAR = 200;

    private final AiClientFactory aiClientFactory;
    private final KnowledgeBaseVectorService vectorService;
    private final KnowledgeBaseCountService countService;
    private final PromptTemplate systemPromptTemplate;
    private final PromptTemplate userPromptTemplate;
    private final PromptTemplate rewritePromptTemplate;
    private final SystemAiSettingsResolver systemAiSettingsResolver;
    private final boolean rewriteEnabled;
    private final int shortQueryLength;
    private final int mediumQueryLength;

    public KnowledgeBaseQueryService(
            AiClientFactory aiClientFactory,
            KnowledgeBaseVectorService vectorService,
            KnowledgeBaseCountService countService,
            SystemAiSettingsResolver systemAiSettingsResolver,
            KnowledgeBaseQueryProperties queryProperties,
            ResourceLoader resourceLoader) throws IOException {
        this.aiClientFactory = aiClientFactory;
        this.vectorService = vectorService;
        this.countService = countService;
        this.systemAiSettingsResolver = systemAiSettingsResolver;
        this.systemPromptTemplate = new PromptTemplate(
                resourceLoader.getResource(queryProperties.getSystemPromptPath())
                        .getContentAsString(StandardCharsets.UTF_8)
        );
        this.userPromptTemplate = new PromptTemplate(
                resourceLoader.getResource(queryProperties.getUserPromptPath())
                        .getContentAsString(StandardCharsets.UTF_8)
        );
        this.rewritePromptTemplate = new PromptTemplate(
                resourceLoader.getResource(queryProperties.getRewritePromptPath())
                        .getContentAsString(StandardCharsets.UTF_8)
        );
        this.rewriteEnabled = queryProperties.getRewrite().isEnabled();
        this.shortQueryLength = queryProperties.getSearch().getShortQueryLength();
        this.mediumQueryLength = queryProperties.getSearch().getMediumQueryLength();
    }

    // 流式回答用户问题，结合知识库检索结果和对话历史生成回复
    public Flux<String> answerQuestionStream(List<Long> knowledgeBaseIds, String question, List<Message> history) {
        log.info(
                "Knowledge base stream question received: kbIds={}, question={}, historySize={}",
                knowledgeBaseIds,
                question,
                history != null ? history.size() : 0
        );
        // 校验参数
        if (knowledgeBaseIds == null || knowledgeBaseIds.isEmpty()
                || normalizeQuestion(question).isBlank()) {
            return Flux.just(NO_RESULT_RESPONSE);
        }

        try {
            // 更新提问次数
            countService.updateQuestionCounts(knowledgeBaseIds);

            QueryContext queryContext = buildQueryContext(question, history);  // 构建查询上下文
            List<Document> relevantDocs = retrieveRelevantDocs(queryContext, knowledgeBaseIds);  // 检索文档
            // 检索不到则返回默认响应
            if (!hasEffectiveHit(relevantDocs)) {
                return Flux.just(NO_RESULT_RESPONSE);
            }

            // 拼接检索到的文档内容作为上下文
            String context = relevantDocs.stream()
                    .map(Document::getText)
                    .collect(Collectors.joining("\n\n---\n\n"));
            log.debug("Retrieved {} relevant document chunks", relevantDocs.size());

            // 构建提示词
            String systemPrompt = buildSystemPrompt();
            String userPrompt = buildUserPrompt(context, question);

            // 加入历史对话
            var promptSpec = getChatClient().prompt().system(systemPrompt);
            if (!history.isEmpty()) {
                promptSpec = promptSpec.messages(history);
            }
            Flux<String> responseFlux = promptSpec
                    .user(userPrompt)
                    .stream()
                    .content();

            log.info("Knowledge base stream output started: kbIds={}", knowledgeBaseIds);
            return normalizeStreamOutput(responseFlux)
                    .doOnComplete(() -> log.info("Knowledge base stream output completed: kbIds={}",
                            knowledgeBaseIds))
                    .onErrorResume(e -> {
                        log.error("Knowledge base stream output failed: kbIds={}", knowledgeBaseIds, e);
                        return Flux.just("【错误】知识库查询失败：AI 服务暂时不可用，请稍后重试。");
                    });
        } catch (Exception e) {
            log.error("Knowledge base stream question failed: kbIds={}", knowledgeBaseIds, e);
            return Flux.just("【错误】知识库查询失败：" + e.getMessage());
        }
    }

    // 获取默认的AI聊天客户端
    private ChatClient getChatClient() {
        return aiClientFactory.getDefaultChatClient();
    }

    // 构建系统提示词，包含角色定义和防注入指令
    private String buildSystemPrompt() {
        return systemPromptTemplate.render() + PromptSecurityConstants.ANTI_INJECTION_INSTRUCTION;
    }

    // 构建用户提示词，填充检索到的知识库上下文和用户问题
    private String buildUserPrompt(String context, String question) {
        Map<String, Object> variables = new HashMap<>();
        variables.put("context", context);
        variables.put("question", question);
        return userPromptTemplate.render(variables);
    }

    // 构建查询上下文，包含原始问题、改写后的问题候选列表和搜索参数
    private QueryContext buildQueryContext(String originalQuestion, List<Message> history) {
        // 改写原问题
        String normalizedQuestion = normalizeQuestion(originalQuestion);
        String rewrittenQuestion = rewriteQuestion(normalizedQuestion, history);

        // 将历史问题和现问题插入LinkedHashSet中
        Set<String> candidates = new LinkedHashSet<>()  ;
        candidates.add(rewrittenQuestion);
        candidates.add(normalizedQuestion);

        // 调整搜索参数
        SearchParams searchParams = resolveSearchParams(normalizedQuestion);
        return new QueryContext(normalizedQuestion, new ArrayList<>(candidates), searchParams);
    }

    // 规范化问题文本，去除首尾空白
    private String normalizeQuestion(String question) {
        return question == null ? "" : question.trim();
    }

    // 检索相关文档，按候选查询优先级依次尝试，返回首次命中的结果
    private List<Document> retrieveRelevantDocs(QueryContext queryContext, List<Long> knowledgeBaseIds) {
        // 遍历问题
        for (String candidateQuery : queryContext.candidateQueries()) {
            if (candidateQuery.isBlank()) {
                continue;
            }
            // 执行向量相似度查询
            List<Document> docs = vectorService.similaritySearch(
                    candidateQuery,
                    knowledgeBaseIds,
                    queryContext.searchParams().topK(),
                    queryContext.searchParams().minScore()
            );
            log.info("Retrieved candidate query='{}', hits={}", candidateQuery, docs.size());
            if (hasEffectiveHit(docs)) {
                return docs;
            }
        }
        return List.of();
    }

    // 根据问题长度动态调整搜索参数：短查询使用较低的相似度阈值和较少的召回数
    private SearchParams resolveSearchParams(String question) {
        RagSearchSnapshot ragSearch = systemAiSettingsResolver.resolve().ragSearch();
        int compactLength = question.replaceAll("\\s+", "").length();
        if (compactLength <= shortQueryLength) {
            return new SearchParams(ragSearch.topkShort(), ragSearch.minScoreShort());
        }
        if (compactLength <= mediumQueryLength) {
            return new SearchParams(ragSearch.topkMedium(), ragSearch.minScoreMedium());
        }
        return new SearchParams(ragSearch.topkLong(), ragSearch.minScoreLong());
    }

    // 基于对话历史改写用户问题，提升检索命中率
    private String rewriteQuestion(String question, List<Message> history) {
        if (!rewriteEnabled || question.isBlank()) {
            return question;
        }
        try {
            // 构建变量
            Map<String, Object> variables = new HashMap<>();
            variables.put("question", question);
            variables.put("history", formatHistoryForRewrite(history));

            String rewritePrompt = rewritePromptTemplate.render(variables);
            String rewritten = getChatClient().prompt()
                    .user(rewritePrompt)
                    .call()
                    .content();
            if (rewritten == null || rewritten.isBlank()) {
                return question;
            }
            String normalized = rewritten.trim();
            log.info(
                    "Query rewrite: origin='{}', rewritten='{}', historySize={}",
                    question,
                    normalized,
                    history.size()
            );
            return normalized;
        } catch (Exception e) {
            log.warn("Query rewrite failed; continuing with original question: {}", e.getMessage());
            return question;
        }
    }

    // 格式化对话历史为问题改写的上下文，截断过长的助手回复
    private String formatHistoryForRewrite(List<Message> history) {
        if (history == null || history.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Message msg : history) {
            if (msg instanceof UserMessage) {
                sb.append("用户: ").append(msg.getText()).append("\n");
            } else if (msg instanceof AssistantMessage) {
                String text = msg.getText();
                if (text.length() > MAX_REWRITE_HISTORY_CHAR) {
                    text = text.substring(0, MAX_REWRITE_HISTORY_CHAR) + "...";
                }
                sb.append("助手: ").append(text).append("\n");
            }
        }
        return sb.toString().trim();
    }

    // 判断检索结果是否有效命中
    private boolean hasEffectiveHit(List<Document> docs) {
        return docs != null && !docs.isEmpty();
    }

    // 规范化AI回复，空内容或无效内容替换为默认无结果提示
    private String normalizeAnswer(String answer) {
        if (answer == null || answer.isBlank()) {
            return NO_RESULT_RESPONSE;
        }
        String normalized = answer.trim();
        if (isNoResultLike(normalized)) {
            return NO_RESULT_RESPONSE;
        }
        return normalized;
    }

    // 检测回复文本是否包含无结果的语义特征
    private boolean isNoResultLike(String text) {
        String lowerText = text.toLowerCase(Locale.ROOT);
        return text.contains("没有找到相关信息")
                || text.contains("未检索到相关信息")
                || text.contains("信息不足")
                || text.contains("超出知识库范围")
                || text.contains("无法根据提供内容回答")
                || lowerText.contains("no relevant information");
    }

    // 规范化流式输出：探测前期chunk是否含无结果语义，命中则提前终止并返回默认提示
    private Flux<String> normalizeStreamOutput(Flux<String> rawFlux) {
        return Flux.create(sink -> {
            StringBuilder probeBuffer = new StringBuilder();
            AtomicBoolean passthrough = new AtomicBoolean(false);
            AtomicBoolean completed = new AtomicBoolean(false);
            final Disposable[] disposableRef = new Disposable[1];

            disposableRef[0] = rawFlux.subscribe(
                    chunk -> {
                        if (completed.get() || sink.isCancelled()) {
                            return;
                        }
                        if (passthrough.get()) {
                            sink.next(chunk);
                            return;
                        }

                        probeBuffer.append(chunk);
                        String probeText = probeBuffer.toString();
                        if (isNoResultLike(probeText)) {
                            completed.set(true);
                            sink.next(NO_RESULT_RESPONSE);
                            sink.complete();
                            if (disposableRef[0] != null) {
                                disposableRef[0].dispose();
                            }
                            return;
                        }

                        if (probeBuffer.length() >= STREAM_PROBE_CHARS) {
                            passthrough.set(true);
                            sink.next(probeText);
                            probeBuffer.setLength(0);
                        }
                    },
                    sink::error,
                    () -> {
                        if (completed.get() || sink.isCancelled()) {
                            return;
                        }
                        if (!passthrough.get()) {
                            sink.next(normalizeAnswer(probeBuffer.toString()));
                        }
                        sink.complete();
                    }
            );

            sink.onCancel(() -> {
                if (disposableRef[0] != null) {
                    disposableRef[0].dispose();
                }
            });
        });
    }

    private record SearchParams(int topK, double minScore) {
    }

    private record QueryContext(
            String originalQuestion,
            List<String> candidateQueries,
            SearchParams searchParams) {
    }
}
