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

    // 创建新的RAG聊天会话
    @Transactional
    public SessionDTO createSession(CreateSessionRequest request) {
        String userId = CurrentUserContext.getRequiredUserId();
        List<KnowledgeBaseEntity> knowledgeBases = knowledgeBaseRepository
            .findAllById(request.knowledgeBaseIds())
            .stream()
            .filter(kb -> userId.equals(kb.getUserId()))
            .toList();

        if (knowledgeBases.size() != request.knowledgeBaseIds().size()) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "Some knowledge bases do not exist");
        }

        RagChatSessionEntity session = new RagChatSessionEntity();
        session.setTitle(request.title() != null && !request.title().isBlank()
            ? request.title()
            : generateTitle(knowledgeBases));
        session.setUserId(userId);
        session.setKnowledgeBases(new HashSet<>(knowledgeBases));

        session = sessionRepository.save(session);

        log.info("Created RAG chat session: id={}, userId={}, title={}",
            session.getId(), userId, session.getTitle());
        return ragChatMapper.toSessionDTO(session);
    }

    // 获取所有会话列表
    public List<SessionListItemDTO> listSessions() {
        return sessionRepository.findAllByUserIdOrderByPinnedAndUpdatedAtDesc(
                CurrentUserContext.getRequiredUserId())
            .stream()
            .map(ragChatMapper::toSessionListItemDTO)
            .toList();
    }

    // 获取会话详情（包含知识库信息和历史消息）
    public SessionDetailDTO getSessionDetail(Long sessionId) {
        RagChatSessionEntity session = sessionRepository.findByIdAndUserIdWithKnowledgeBases(
                sessionId,
                CurrentUserContext.getRequiredUserId())
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Session not found"));

        List<RagChatMessageEntity> messages = messageRepository
            .findBySessionIdOrderByMessageOrderAsc(sessionId);
        List<KnowledgeBaseListItemDTO> kbDTOs = knowledgeBaseMapper.toListItemDTOList(
            new ArrayList<>(session.getKnowledgeBases())
        );

        return ragChatMapper.toSessionDetailDTO(session, messages, kbDTOs);
    }

    // 为流式响应做准备：保存用户消息，创建空的AI回复消息
    @Transactional
    public Long prepareStreamMessage(Long sessionId, String question) {
        RagChatSessionEntity session = sessionRepository.findByIdAndUserIdWithKnowledgeBases(
                sessionId,
                CurrentUserContext.getRequiredUserId())
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Session not found"));

        int nextOrder = session.getMessageCount();

        RagChatMessageEntity userMessage = new RagChatMessageEntity();
        userMessage.setSession(session);
        userMessage.setType(RagChatMessageEntity.MessageType.USER);
        userMessage.setContent(question);
        userMessage.setMessageOrder(nextOrder);
        userMessage.setCompleted(true);
        messageRepository.save(userMessage);

        RagChatMessageEntity assistantMessage = new RagChatMessageEntity();
        assistantMessage.setSession(session);
        assistantMessage.setType(RagChatMessageEntity.MessageType.ASSISTANT);
        assistantMessage.setContent("");
        assistantMessage.setMessageOrder(nextOrder + 1);
        assistantMessage.setCompleted(false);
        assistantMessage = messageRepository.save(assistantMessage);

        session.setMessageCount(nextOrder + 2);
        sessionRepository.save(session);

        log.info("Prepared stream message: sessionId={}, messageId={}", sessionId, assistantMessage.getId());
        return assistantMessage.getId();
    }

    // 完成流式消息：保存完整的AI回复内容
    @Transactional
    public void completeStreamMessage(Long messageId, String content) {
        RagChatMessageEntity message = messageRepository.findById(messageId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Message not found"));

        message.setContent(content);
        message.setCompleted(true);
        messageRepository.save(message);

        log.info("Completed stream message: messageId={}, contentLength={}", messageId, content.length());
    }

    // 获取流式AI回答
    public Flux<String> getStreamAnswer(Long sessionId, String question) {
        RagChatSessionEntity session = sessionRepository.findByIdAndUserIdWithKnowledgeBases(
                sessionId,
                CurrentUserContext.getRequiredUserId())
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Session not found"));

        List<Long> kbIds = session.getKnowledgeBaseIds();
        List<Message> history = queryProperties.getHistory().isEnabled()
            ? loadHistoryMessages(sessionId)
            : List.of();

        log.info("Loaded RAG history: sessionId={}, historySize={}", sessionId, history.size());
        return queryService.answerQuestionStream(kbIds, question, history);
    }

    // 更新会话标题
    @Transactional
    public void updateSessionTitle(Long sessionId, String title) {
        RagChatSessionEntity session = sessionRepository.findByIdAndUserIdWithKnowledgeBases(
                sessionId,
                CurrentUserContext.getRequiredUserId())
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Session not found"));

        session.setTitle(title);
        sessionRepository.save(session);

        log.info("Updated session title: sessionId={}, title={}", sessionId, title);
    }

    // 切换会话的置顶状态
    @Transactional
    public void togglePin(Long sessionId) {
        RagChatSessionEntity session = sessionRepository.findByIdAndUserIdWithKnowledgeBases(
                sessionId,
                CurrentUserContext.getRequiredUserId())
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Session not found"));

        Boolean currentPinned = session.getIsPinned() != null ? session.getIsPinned() : false;
        session.setIsPinned(!currentPinned);
        sessionRepository.save(session);

        log.info("Toggled session pin: sessionId={}, isPinned={}", sessionId, session.getIsPinned());
    }

    // 删除会话及其关联的所有消息
    @Transactional
    public void deleteSession(Long sessionId) {
        if (!sessionRepository.existsByIdAndUserId(sessionId, CurrentUserContext.getRequiredUserId())) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "Session not found");
        }
        sessionRepository.deleteById(sessionId);

        log.info("Deleted RAG chat session: sessionId={}", sessionId);
    }

    // 加载历史消息作为RAG的上下文
    private List<Message> loadHistoryMessages(Long sessionId) {
        int limit = queryProperties.getHistory().getMaxMessages() + 1;
        List<RagChatMessageEntity> recent = messageRepository
            .findRecentCompletedBySessionId(sessionId, PageRequest.of(0, limit));

        if (recent.isEmpty()) {
            return List.of();
        }

        List<RagChatMessageEntity> historyMessages = recent.size() <= 1
            ? List.of()
            : recent.subList(1, recent.size());

        return historyMessages.reversed().stream()
            .map(m -> m.getType() == RagChatMessageEntity.MessageType.USER
                ? (Message) new UserMessage(m.getContent())
                : (Message) new AssistantMessage(m.getContent()))
            .toList();
    }

    // 根据关联的知识库自动生成会话标题
    private String generateTitle(List<KnowledgeBaseEntity> knowledgeBases) {
        if (knowledgeBases.isEmpty()) {
            return "New chat";
        }
        if (knowledgeBases.size() == 1) {
            return knowledgeBases.getFirst().getName();
        }
        return knowledgeBases.size() + " knowledge bases chat";
    }
}
