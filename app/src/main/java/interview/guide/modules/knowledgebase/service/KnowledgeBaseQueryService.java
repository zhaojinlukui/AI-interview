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

/**
 * 知识库查询服务
 * 负责基于知识库的RAG问答，包括问题改写、向量检索、上下文拼接和流式回答生成，
 * 支持根据问题长度动态调整搜索参数，检索无结果时返回友好提示
 */
@Slf4j
@Service
public class KnowledgeBaseQueryService {

    private static final String NO_RESULT_RESPONSE = "抱歉，在选定的知识库中未检索到相关信息。" + "请换一个更具体的关键词或补充上下文后再试。"; // 无结果默认回复
    private static final int STREAM_PROBE_CHARS = 120; // 流式输出探测字符数，用于提前判断是否为无结果回复
    private static final int MAX_REWRITE_HISTORY_CHAR = 200; // 问题改写时单条历史消息的最大字符数

    private final AiClientFactory aiClientFactory; // AI客户端工厂
    private final KnowledgeBaseVectorService vectorService; // 知识库向量检索服务
    private final KnowledgeBaseCountService countService; // 知识库提问次数统计服务
    private final PromptTemplate systemPromptTemplate; // 系统提示词模板
    private final PromptTemplate userPromptTemplate; // 用户提示词模板
    private final PromptTemplate rewritePromptTemplate; // 问题改写提示词模板
    private final SystemAiSettingsResolver systemAiSettingsResolver; // 系统AI配置解析器
    private final boolean rewriteEnabled; // 是否启用问题改写
    private final int shortQueryLength; // 短查询长度阈值
    private final int mediumQueryLength; // 中等查询长度阈值

    // 构造函数，初始化各类提示词模板和配置参数
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
        // 加载系统提示词模板
        this.systemPromptTemplate = new PromptTemplate(
                resourceLoader.getResource(queryProperties.getSystemPromptPath())
                        .getContentAsString(StandardCharsets.UTF_8)
        );
        // 加载用户提示词模板
        this.userPromptTemplate = new PromptTemplate(
                resourceLoader.getResource(queryProperties.getUserPromptPath())
                        .getContentAsString(StandardCharsets.UTF_8)
        );
        // 加载问题改写提示词模板
        this.rewritePromptTemplate = new PromptTemplate(
                resourceLoader.getResource(queryProperties.getRewritePromptPath())
                        .getContentAsString(StandardCharsets.UTF_8)
        );
        this.rewriteEnabled = queryProperties.getRewrite().isEnabled();
        this.shortQueryLength = queryProperties.getSearch().getShortQueryLength();
        this.mediumQueryLength = queryProperties.getSearch().getMediumQueryLength();
    }

    // 流式回答用户问题，结合知识库检索结果和对话历史生成流式回复，检索不到相关文档时返回友好提示
    public Flux<String> answerQuestionStream(List<Long> knowledgeBaseIds, String question, List<Message> history) {
        log.info(
                "知识库流式问题已接收: kbIds={}, question={}, historySize={}",
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
            // 更新知识库提问次数统计
            countService.updateQuestionCounts(knowledgeBaseIds);

            // 构建查询上下文（含问题改写和搜索参数）
            QueryContext queryContext = buildQueryContext(question, history);
            // 向量检索相关文档
            List<Document> relevantDocs = retrieveRelevantDocs(queryContext, knowledgeBaseIds);
            // 检索不到则返回默认响应
            if (!hasEffectiveHit(relevantDocs)) {
                return Flux.just(NO_RESULT_RESPONSE);
            }

            // 拼接检索到的文档内容作为上下文
            String context = relevantDocs.stream()
                    .map(Document::getText)
                    .collect(Collectors.joining("\n\n---\n\n"));
            log.debug("检索到 {} 个相关文档片段", relevantDocs.size());

            // 构建提示词
            String systemPrompt = buildSystemPrompt();
            String userPrompt = buildUserPrompt(context, question);

            // 组装提示词并加入历史对话，生成流式回答
            var promptSpec = getChatClient().prompt().system(systemPrompt);
            if (!history.isEmpty()) {
                promptSpec = promptSpec.messages(history);
            }
            Flux<String> responseFlux = promptSpec
                    .user(userPrompt)
                    .stream()
                    .content();

            log.info("知识库流式输出已开始: kbIds={}", knowledgeBaseIds);
            // 规范化流式输出，提前探测无结果回复
            return normalizeStreamOutput(responseFlux)
                    .doOnComplete(() -> log.info("知识库流式输出已完成: kbIds={}", knowledgeBaseIds))
                    .onErrorResume(e -> {
                        log.error("知识库流式输出失败: kbIds={}", knowledgeBaseIds, e);
                        return Flux.just("【错误】知识库查询失败：AI 服务暂时不可用，请稍后重试。");
                    });
        } catch (Exception e) {
            log.error("知识库流式问题处理失败: kbIds={}", knowledgeBaseIds, e);
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
        // 规范化并改写原问题
        String normalizedQuestion = normalizeQuestion(originalQuestion);
        String rewrittenQuestion = rewriteQuestion(normalizedQuestion, history);

        // 将改写后的问题和原问题加入候选列表，优先使用改写结果
        Set<String> candidates = new LinkedHashSet<>();
        candidates.add(rewrittenQuestion);
        candidates.add(normalizedQuestion);

        // 根据问题长度动态调整搜索参数
        SearchParams searchParams = resolveSearchParams(normalizedQuestion);
        return new QueryContext(normalizedQuestion, new ArrayList<>(candidates), searchParams);
    }

    // 规范化问题文本，去除首尾空白
    private String normalizeQuestion(String question) {
        return question == null ? "" : question.trim();
    }

    // 检索相关文档，按候选查询优先级依次尝试，返回首次命中的结果
    private List<Document> retrieveRelevantDocs(QueryContext queryContext, List<Long> knowledgeBaseIds) {
        // 按优先级遍历候选问题列表
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
            log.info("检索候选查询='{}', 命中数={}", candidateQuery, docs.size());
            if (hasEffectiveHit(docs)) {
                return docs;
            }
        }
        return List.of();
    }

    // 根据问题长度动态调整搜索参数，短查询使用较低的相似度阈值和较少的召回数，长查询反之
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

    // 基于对话历史改写用户问题，利用AI将口语化或指代不明的问题转换为更精准的检索查询，改写失败时回退到原始问题
    private String rewriteQuestion(String question, List<Message> history) {
        if (!rewriteEnabled || question.isBlank()) {
            return question;
        }
        try {
            // 构建改写提示词变量
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
                    "问题改写: 原文='{}', 改写后='{}', 历史消息数={}",
                    question,
                    normalized,
                    history.size()
            );
            return normalized;
        } catch (Exception e) {
            log.warn("问题改写失败，继续使用原始问题: {}", e.getMessage());
            return question;
        }
    }

    // 格式化对话历史为问题改写的上下文，截断过长的助手回复以控制输入长度
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
                // 截断过长的助手回复
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

    // 规范化流式输出，探测前期chunk是否包含无结果语义，命中则提前终止并返回默认提示，超过探测字符数后转为透传模式正常输出
    private Flux<String> normalizeStreamOutput(Flux<String> rawFlux) {
        return Flux.create(sink -> {
            StringBuilder probeBuffer = new StringBuilder(); // 探测缓冲区
            AtomicBoolean passthrough = new AtomicBoolean(false); // 是否进入透传模式
            AtomicBoolean completed = new AtomicBoolean(false); // 是否已完成
            final Disposable[] disposableRef = new Disposable[1];

            disposableRef[0] = rawFlux.subscribe(
                    chunk -> {
                        if (completed.get() || sink.isCancelled()) {
                            return;
                        }
                        // 透传模式：直接发送chunk
                        if (passthrough.get()) {
                            sink.next(chunk);
                            return;
                        }

                        // 探测模式：累积chunk并检查是否为无结果回复
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

                        // 超过探测字符数，转为透传模式，发送已累积的内容
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
                        // 流结束但未进入透传模式，发送缓冲区内容
                        if (!passthrough.get()) {
                            sink.next(normalizeAnswer(probeBuffer.toString()));
                        }
                        sink.complete();
                    }
            );

            // 取消订阅时释放上游资源
            sink.onCancel(() -> {
                if (disposableRef[0] != null) {
                    disposableRef[0].dispose();
                }
            });
        });
    }

    /**
     * 搜索参数记录
     */
    private record SearchParams(int topK, double minScore) { }

    /**
     * 查询上下文记录
     */
    private record QueryContext(
            String originalQuestion, // 原始问题
            List<String> candidateQueries, // 候选查询列表（按优先级排列）
            SearchParams searchParams // 搜索参数
    ) {
    }
}