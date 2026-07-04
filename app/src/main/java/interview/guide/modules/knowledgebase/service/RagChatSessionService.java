package interview.guide.modules.knowledgebase.service;

import interview.guide.common.auth.CurrentUserContext;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.infrastructure.mapper.KnowledgeBaseMapper;
import interview.guide.infrastructure.mapper.RagChatMapper;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseEntity;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseListItemDTO;
import interview.guide.modules.knowledgebase.model.RagChatDTO.CreateSessionRequest;
import interview.guide.modules.knowledgebase.model.RagChatDTO.SessionDTO;
import interview.guide.modules.knowledgebase.model.RagChatDTO.SessionDetailDTO;
import interview.guide.modules.knowledgebase.model.RagChatDTO.SessionListItemDTO;
import interview.guide.modules.knowledgebase.model.RagChatMessageEntity;
import interview.guide.modules.knowledgebase.model.RagChatSessionEntity;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseRepository;
import interview.guide.modules.knowledgebase.repository.RagChatMessageRepository;
import interview.guide.modules.knowledgebase.repository.RagChatSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/**
 * RAG知识库聊天会话服务
 * 负责知识库聊天会话的创建、查询、更新和删除，
 * 支持流式对话、历史消息上下文加载和会话标题自动生成
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagChatSessionService {

    private final RagChatSessionRepository sessionRepository;
    private final RagChatMessageRepository messageRepository;
    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final KnowledgeBaseQueryService queryService;
    private final RagChatMapper ragChatMapper;
    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final KnowledgeBaseQueryProperties queryProperties;

    // 创建新的RAG聊天会话，校验用户对所关联知识库的权限，自动生成会话标题
    @Transactional
    public SessionDTO createSession(CreateSessionRequest request) {
        String userId = CurrentUserContext.getRequiredUserId();
        // 加载知识库并校验归属权限
        List<KnowledgeBaseEntity> knowledgeBases = knowledgeBaseRepository
                .findAllById(request.knowledgeBaseIds())
                .stream()
                .filter(kb -> userId.equals(kb.getUserId()))
                .toList();

        if (knowledgeBases.size() != request.knowledgeBaseIds().size()) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "部分知识库不存在");
        }

        RagChatSessionEntity session = new RagChatSessionEntity();
        // 使用指定标题或根据知识库自动生成
        session.setTitle(request.title() != null && !request.title().isBlank()
                ? request.title()
                : generateTitle(knowledgeBases));
        session.setUserId(userId);
        session.setKnowledgeBases(new HashSet<>(knowledgeBases));

        session = sessionRepository.save(session);

        log.info("RAG聊天会话已创建: id={}, userId={}, title={}",
                session.getId(), userId, session.getTitle());
        return ragChatMapper.toSessionDTO(session);
    }

    // 获取当前用户的所有会话列表，按置顶优先、更新时间降序排列
    public List<SessionListItemDTO> listSessions() {
        return sessionRepository.findAllByUserIdOrderByPinnedAndUpdatedAtDesc(
                        CurrentUserContext.getRequiredUserId())
                .stream()
                .map(ragChatMapper::toSessionListItemDTO)
                .toList();
    }

    // 获取会话详情，包含关联的知识库信息和历史消息列表
    public SessionDetailDTO getSessionDetail(Long sessionId) {
        RagChatSessionEntity session = sessionRepository.findByIdAndUserIdWithKnowledgeBases(
                        sessionId,
                        CurrentUserContext.getRequiredUserId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "会话不存在"));

        List<RagChatMessageEntity> messages = messageRepository
                .findBySessionIdOrderByMessageOrderAsc(sessionId);
        List<KnowledgeBaseListItemDTO> kbDTOs = knowledgeBaseMapper.toListItemDTOList(
                new ArrayList<>(session.getKnowledgeBases())
        );

        return ragChatMapper.toSessionDetailDTO(session, messages, kbDTOs);
    }

    // 为流式响应做准备，保存用户消息并创建空的AI回复消息，返回AI消息ID供后续填充
    @Transactional
    public Long prepareStreamMessage(Long sessionId, String question) {
        RagChatSessionEntity session = sessionRepository.findByIdAndUserIdWithKnowledgeBases(
                        sessionId,
                        CurrentUserContext.getRequiredUserId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "会话不存在"));

        // 计算消息序号
        int nextOrder = session.getMessageCount();

        // 保存用户消息
        RagChatMessageEntity userMessage = new RagChatMessageEntity();
        userMessage.setSession(session);
        userMessage.setType(RagChatMessageEntity.MessageType.USER);
        userMessage.setContent(question);
        userMessage.setMessageOrder(nextOrder);
        userMessage.setCompleted(true);
        messageRepository.save(userMessage);

        // 创建空的AI回复消息，内容将在流式输出过程中逐步填充
        RagChatMessageEntity assistantMessage = new RagChatMessageEntity();
        assistantMessage.setSession(session);
        assistantMessage.setType(RagChatMessageEntity.MessageType.ASSISTANT);
        assistantMessage.setContent("");
        assistantMessage.setMessageOrder(nextOrder + 1);
        assistantMessage.setCompleted(false);
        assistantMessage = messageRepository.save(assistantMessage);

        // 更新会话消息计数
        session.setMessageCount(nextOrder + 2);
        sessionRepository.save(session);

        log.info("流式消息已准备: sessionId={}, messageId={}", sessionId, assistantMessage.getId());
        return assistantMessage.getId();
    }

    // 完成流式消息，保存完整的AI回复内容并标记为已完成
    @Transactional
    public void completeStreamMessage(Long messageId, String content) {
        RagChatMessageEntity message = messageRepository.findById(messageId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "消息不存在"));

        message.setContent(content);
        message.setCompleted(true);
        messageRepository.save(message);

        log.info("流式消息已完成: messageId={}, contentLength={}", messageId, content.length());
    }

    // 获取流式AI回答，加载历史消息作为上下文，通过知识库查询服务生成流式回答
    public Flux<String> getStreamAnswer(Long sessionId, String question) {
        RagChatSessionEntity session = sessionRepository.findByIdAndUserIdWithKnowledgeBases(
                        sessionId,
                        CurrentUserContext.getRequiredUserId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "会话不存在"));

        List<Long> kbIds = session.getKnowledgeBaseIds();
        // 根据配置决定是否加载历史消息作为上下文
        List<Message> history = queryProperties.getHistory().isEnabled()
                ? loadHistoryMessages(sessionId)
                : List.of();

        log.info("RAG历史已加载: sessionId={}, historySize={}", sessionId, history.size());
        return queryService.answerQuestionStream(kbIds, question, history);
    }

    // 更新会话标题
    @Transactional
    public void updateSessionTitle(Long sessionId, String title) {
        RagChatSessionEntity session = sessionRepository.findByIdAndUserIdWithKnowledgeBases(
                        sessionId,
                        CurrentUserContext.getRequiredUserId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "会话不存在"));

        session.setTitle(title);
        sessionRepository.save(session);

        log.info("会话标题已更新: sessionId={}, title={}", sessionId, title);
    }

    // 切换会话的置顶状态
    @Transactional
    public void togglePin(Long sessionId) {
        RagChatSessionEntity session = sessionRepository.findByIdAndUserIdWithKnowledgeBases(
                        sessionId,
                        CurrentUserContext.getRequiredUserId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "会话不存在"));

        // 取反当前置顶状态
        Boolean currentPinned = session.getIsPinned() != null ? session.getIsPinned() : false;
        session.setIsPinned(!currentPinned);
        sessionRepository.save(session);

        log.info("会话置顶状态已切换: sessionId={}, isPinned={}", sessionId, session.getIsPinned());
    }

    // 删除会话及其关联的所有消息，使用级联删除，同时清理消息记录
    @Transactional
    public void deleteSession(Long sessionId) {
        if (!sessionRepository.existsByIdAndUserId(sessionId, CurrentUserContext.getRequiredUserId())) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "会话不存在");
        }
        sessionRepository.deleteById(sessionId);

        log.info("RAG聊天会话已删除: sessionId={}", sessionId);
    }

    // 加载历史消息作为RAG的上下文，取最近的已完成消息，排除当前正在进行的消息，反转顺序使最新的消息排在最后
    private List<Message> loadHistoryMessages(Long sessionId) {
        int limit = queryProperties.getHistory().getMaxMessages() + 1;
        List<RagChatMessageEntity> recent = messageRepository
                .findRecentCompletedBySessionId(sessionId, PageRequest.of(0, limit));

        if (recent.isEmpty()) {
            return List.of();
        }

        // 排除最后一条（当前正在流式输出的消息），反转顺序后转换
        List<RagChatMessageEntity> historyMessages = recent.size() <= 1
                ? List.of()
                : recent.subList(1, recent.size());

        return historyMessages.reversed().stream()
                .map(m -> m.getType() == RagChatMessageEntity.MessageType.USER
                        ? (Message) new UserMessage(m.getContent())
                        : (Message) new AssistantMessage(m.getContent()))
                .toList();
    }

    // 根据关联的知识库自动生成会话标题，单个知识库使用其名称，多个知识库显示数量
    private String generateTitle(List<KnowledgeBaseEntity> knowledgeBases) {
        if (knowledgeBases.isEmpty()) {
            return "新的聊天";
        }
        if (knowledgeBases.size() == 1) {
            return knowledgeBases.getFirst().getName();
        }
        return knowledgeBases.size() + " 个知识库的聊天";
    }
}