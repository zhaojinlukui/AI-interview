package interview.guide.modules.voiceinterview;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.model.AsyncTaskStatus;
import interview.guide.common.result.Result;
import interview.guide.modules.voiceinterview.dto.CreateSessionRequest;
import interview.guide.modules.voiceinterview.dto.SessionMetaDTO;
import interview.guide.modules.voiceinterview.dto.SessionResponseDTO;
import interview.guide.modules.voiceinterview.dto.VoiceEvaluationDetailDTO;
import interview.guide.modules.voiceinterview.dto.VoiceEvaluationStatusDTO;
import interview.guide.modules.voiceinterview.dto.VoiceInterviewMessageDTO;
import interview.guide.modules.voiceinterview.model.VoiceInterviewSessionEntity;
import interview.guide.modules.voiceinterview.service.VoiceInterviewEvaluationService;
import interview.guide.modules.voiceinterview.service.VoiceInterviewService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.util.List;
import java.util.Map;

/**
 * 语音面试控制器。
 *
 * <p>提供语音面试会话创建、结束、暂停、恢复、消息历史查询、异步评估触发和状态轮询接口。</p>
 */
@RestController
@RequestMapping("/api/voice-interview")
@RequiredArgsConstructor
@Slf4j
public class VoiceInterviewController {

    private final VoiceInterviewService voiceInterviewService;
    private final VoiceInterviewEvaluationService evaluationService;

    /**
     * 创建新的语音面试会话。
     *
     * @param request 创建请求
     * @return 会话信息
     */
    @PostMapping("/sessions")
    public Result<SessionResponseDTO> createSession(@Valid @RequestBody CreateSessionRequest request) {
        log.info("创建语音面试会话: roleType={}", request.getRoleType());
        SessionResponseDTO session = enrichWebSocketUrl(voiceInterviewService.createSession(request));
        return Result.success(session);
    }

    /**
     * 结束语音面试会话，并触发异步评估。
     *
     * @param sessionId 会话 ID
     * @return 结束结果
     */
    @PostMapping("/sessions/{sessionId}/end")
    public Result<Void> endSession(@PathVariable Long sessionId) {
        log.info("结束语音面试会话: sessionId={}", sessionId);
        voiceInterviewService.endSessionForCurrentUser(sessionId);
        return Result.success();
    }

    /**
     * 暂停语音面试会话。
     *
     * @param sessionId 会话 ID
     * @param request 暂停请求
     * @return 暂停结果
     */
    @PutMapping("/sessions/{sessionId}/pause")
    public Result<Void> pauseSession(
            @PathVariable Long sessionId,
            @RequestBody Map<String, String> request
    ) {
        log.info("暂停语音面试会话: sessionId={}", sessionId);
        String reason = request.getOrDefault("reason", "user_initiated");
        voiceInterviewService.pauseSession(sessionId.toString(), reason);
        return Result.success();
    }

    /**
     * 恢复语音面试会话。
     *
     * @param sessionId 会话 ID
     * @return 恢复后的会话信息
     */
    @PutMapping("/sessions/{sessionId}/resume")
    public Result<SessionResponseDTO> resumeSession(@PathVariable Long sessionId) {
        log.info("恢复语音面试会话: sessionId={}", sessionId);
        SessionResponseDTO session = enrichWebSocketUrl(
                voiceInterviewService.resumeSession(sessionId.toString()));
        return Result.success(session);
    }

    /**
     * 查询用户语音面试会话列表。
     *
     * @param status 状态过滤，可选
     * @return 会话列表
     */
    @GetMapping("/sessions")
    public Result<List<SessionMetaDTO>> getAllSessions(
            @RequestParam(required = false) String status
    ) {
        log.debug("查询语音面试会话列表: status={}", status);
        List<SessionMetaDTO> sessions = voiceInterviewService.getAllSessions(status);
        return Result.success(sessions);
    }

    /**
     * 删除语音面试会话。
     *
     * @param sessionId 会话 ID
     * @return 删除结果
     */
    @DeleteMapping("/sessions/{sessionId}")
    public Result<Void> deleteSession(@PathVariable Long sessionId) {
        log.info("删除语音面试会话: sessionId={}", sessionId);
        voiceInterviewService.deleteSession(sessionId);
        return Result.success();
    }

    /**
     * 查询语音面试对话历史。
     *
     * @param sessionId 会话 ID
     * @return 对话消息列表
     */
    @GetMapping("/sessions/{sessionId}/messages")
    public Result<List<VoiceInterviewMessageDTO>> getMessages(@PathVariable Long sessionId) {
        log.info("查询语音面试消息: sessionId={}", sessionId);
        List<VoiceInterviewMessageDTO> messages =
                voiceInterviewService.getConversationHistoryDTO(sessionId.toString());
        return Result.success(messages);
    }

    /**
     * 查询语音面试评估状态和结果。
     *
     * <p>评估完成时返回评估详情；未完成时返回当前异步任务状态，前端可轮询该接口。</p>
     *
     * @param sessionId 会话 ID
     * @return 评估状态和结果
     */
    @GetMapping("/sessions/{sessionId}/evaluation")
    public Result<VoiceEvaluationStatusDTO> getEvaluation(@PathVariable Long sessionId) {
        log.info("查询语音面试评估状态: sessionId={}", sessionId);

        VoiceInterviewSessionEntity session = voiceInterviewService.getSessionForCurrentUser(sessionId);

        AsyncTaskStatus status = session.getEvaluateStatus();
        VoiceEvaluationStatusDTO.VoiceEvaluationStatusDTOBuilder builder =
                VoiceEvaluationStatusDTO.builder()
                        .evaluateStatus(status != null ? status.name() : null)
                        .evaluateError(session.getEvaluateError());

        if (status == AsyncTaskStatus.COMPLETED) {
            VoiceEvaluationDetailDTO evaluation = evaluationService.getEvaluation(sessionId);
            builder.evaluation(evaluation);
        }

        return Result.success(builder.build());
    }

    /**
     * 触发语音面试异步评估。
     *
     * <p>如果评估已完成则直接返回结果；如果评估正在进行则返回当前状态；否则投递新的评估任务。</p>
     *
     * @param sessionId 会话 ID
     * @return 评估状态
     */
    @PostMapping("/sessions/{sessionId}/evaluation")
    public Result<VoiceEvaluationStatusDTO> generateEvaluation(@PathVariable Long sessionId) {
        log.info("触发语音面试异步评估: sessionId={}", sessionId);

        VoiceInterviewSessionEntity session = voiceInterviewService.getSessionForCurrentUser(sessionId);

        // 已完成时直接返回缓存结果。
        if (session.getEvaluateStatus() == AsyncTaskStatus.COMPLETED) {
            VoiceEvaluationDetailDTO evaluation = evaluationService.getEvaluation(sessionId);
            return Result.success(VoiceEvaluationStatusDTO.builder()
                    .evaluateStatus(AsyncTaskStatus.COMPLETED.name())
                    .evaluation(evaluation)
                    .build());
        }

        // 已经排队或处理中时返回当前状态。
        if (session.getEvaluateStatus() == AsyncTaskStatus.PENDING
                || session.getEvaluateStatus() == AsyncTaskStatus.PROCESSING) {
            return Result.success(VoiceEvaluationStatusDTO.builder()
                    .evaluateStatus(session.getEvaluateStatus().name())
                    .build());
        }

        // 投递新的异步评估任务。
        voiceInterviewService.triggerEvaluation(sessionId);

        return Result.success(VoiceEvaluationStatusDTO.builder()
                .evaluateStatus(AsyncTaskStatus.PENDING.name())
                .build());
    }

    private SessionResponseDTO enrichWebSocketUrl(SessionResponseDTO session) {
        if (session == null || session.getSessionId() == null) {
            return session;
        }
        var contextUri = ServletUriComponentsBuilder.fromCurrentContextPath().build().toUri();
        String wsScheme = "https".equalsIgnoreCase(contextUri.getScheme()) ? "wss" : "ws";
        String authority = contextUri.getAuthority();
        session.setWebSocketUrl(String.format("%s://%s/ws/voice-interview/%d",
                wsScheme, authority, session.getSessionId()));
        return session;
    }
}
