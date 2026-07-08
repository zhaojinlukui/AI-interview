package interview.guide.modules.knowledgebase;

import interview.guide.common.result.Result;
import interview.guide.modules.knowledgebase.model.RagChatDTO.CreateSessionRequest;
import interview.guide.modules.knowledgebase.model.RagChatDTO.SendMessageRequest;
import interview.guide.modules.knowledgebase.model.RagChatDTO.SessionDTO;
import interview.guide.modules.knowledgebase.model.RagChatDTO.SessionDetailDTO;
import interview.guide.modules.knowledgebase.model.RagChatDTO.SessionListItemDTO;
import interview.guide.modules.knowledgebase.model.RagChatDTO.UpdateTitleRequest;
import interview.guide.modules.knowledgebase.service.RagChatSessionService;
import jakarta.validation.Valid;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * RAG 聊天会话控制器
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@Tag(name = "RAG 聊天", description = "知识库聊天会话管理")
public class RagChatController {

    private final RagChatSessionService sessionService;

    /**
     * 创建 RAG 聊天会话
     */
    @PostMapping("/api/rag-chat/sessions")
    public Result<SessionDTO> createSession(@Valid @RequestBody CreateSessionRequest request) {
        return Result.success(sessionService.createSession(request));
    }

    /**
     * 查询 RAG 聊天会话列表
     */
    @GetMapping("/api/rag-chat/sessions")
    public Result<List<SessionListItemDTO>> listSessions() {
        return Result.success(sessionService.listSessions());
    }

    /**
     * 查询 RAG 聊天会话详情
     */
    @GetMapping("/api/rag-chat/sessions/{sessionId}")
    public Result<SessionDetailDTO> getSessionDetail(@PathVariable Long sessionId) {
        return Result.success(sessionService.getSessionDetail(sessionId));
    }

    /**
     * 更新 RAG 聊天会话标题
     */
    @PutMapping("/api/rag-chat/sessions/{sessionId}/title")
    public Result<Void> updateSessionTitle(
            @PathVariable Long sessionId,
            @Valid @RequestBody UpdateTitleRequest request
    ) {
        sessionService.updateSessionTitle(sessionId, request.title());
        return Result.success(null);
    }

    /**
     * 切换 RAG 聊天会话置顶状态
     */
    @PutMapping("/api/rag-chat/sessions/{sessionId}/pin")
    public Result<Void> togglePin(@PathVariable Long sessionId) {
        sessionService.togglePin(sessionId);
        return Result.success(null);
    }

    /**
     * 删除 RAG 聊天会话
     */
    @DeleteMapping("/api/rag-chat/sessions/{sessionId}")
    public Result<Void> deleteSession(@PathVariable Long sessionId) {
        sessionService.deleteSession(sessionId);
        return Result.success(null);
    }

    /**
     * 发送问题并以 SSE 流式返回 RAG 回答
     *
     * <p>处理流程：</p>
     * <ol>
     *   <li>先保存用户消息并创建空的 AI 回复消息</li>
     *   <li>逐块转发模型输出，同时拼接完整回答</li>
     *   <li>流结束后把完整 AI 回复写回消息记录</li>
     * </ol>
     */
    @PostMapping(
            value = "/api/rag-chat/sessions/{sessionId}/messages/stream",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE
    )
    public Flux<ServerSentEvent<String>> sendMessageStream(
            @PathVariable Long sessionId,
            @Valid @RequestBody SendMessageRequest request
    ) {
        log.info("发送 RAG 流式消息: sessionId={}, question={}, thread={} (virtual={})",
                sessionId, request.question(), Thread.currentThread(), Thread.currentThread().isVirtual());

        Long messageId = sessionService.prepareStreamMessage(sessionId, request.question());

        // 拼接完整回复，供流式输出结束后回写数据库
        StringBuilder fullContent = new StringBuilder();

        return sessionService.getStreamAnswer(sessionId, request.question())
                .doOnNext(fullContent::append)
                // SSE 单个 data 字段不直接保留换行，前端再按转义字符还原
                .map(chunk -> ServerSentEvent.<String>builder()
                        .data(chunk.replace("\n", "\\n").replace("\r", "\\r"))
                        .build())
                .doOnComplete(() -> {
                    sessionService.completeStreamMessage(messageId, fullContent.toString());
                    log.info("RAG 流式消息完成: sessionId={}, messageId={}", sessionId, messageId);
                })
                .doOnError(e -> {
                    String content = !fullContent.isEmpty()
                            ? fullContent.toString()
                            : "[错误] 回答生成失败: " + e.getMessage();
                    sessionService.completeStreamMessage(messageId, content);
                    log.error("RAG 流式消息失败: sessionId={}", sessionId, e);
                });
    }
}
