package interview.guide.modules.resume.listener;

import interview.guide.common.async.AbstractStreamConsumer;
import interview.guide.common.async.AsyncMessageId;
import interview.guide.common.constant.AsyncTaskStreamConstants;
import interview.guide.common.model.AsyncTaskStatus;
import interview.guide.infrastructure.rabbitmq.RabbitMqTaskQueueService;
import interview.guide.modules.interview.model.ResumeAnalysisResponse;
import interview.guide.modules.resume.model.ResumeEntity;
import interview.guide.modules.resume.repository.ResumeRepository;
import interview.guide.modules.resume.service.ResumeGradingService;
import interview.guide.modules.resume.service.ResumePersistenceService;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 简历分析异步消费者
 * 从消息队列消费简历分析任务，调用AI评分服务进行分析，
 * 支持任务状态更新、失败重试和断线重连
 */
@Slf4j
@Component
public class AnalyzeStreamConsumer extends AbstractStreamConsumer<AnalyzeStreamConsumer.AnalyzePayload> {

    private final ResumeGradingService gradingService;
    private final ResumePersistenceService persistenceService;
    private final ResumeRepository resumeRepository;

    public AnalyzeStreamConsumer(
            RabbitMqTaskQueueService queueService,
            ResumeGradingService gradingService,
            ResumePersistenceService persistenceService,
            ResumeRepository resumeRepository
    ) {
        super(queueService);
        this.gradingService = gradingService;
        this.persistenceService = persistenceService;
        this.resumeRepository = resumeRepository;
    }

    /**
     * 分析任务载荷
     */
    record AnalyzePayload(Long resumeId, String content) {
    }

    @Override
    protected String taskDisplayName() {
        return "简历分析";
    }

    @Override
    protected String streamKey() {
        return AsyncTaskStreamConstants.RESUME_ANALYZE_STREAM_KEY;
    }

    @Override
    protected String groupName() {
        return AsyncTaskStreamConstants.RESUME_ANALYZE_GROUP_NAME;
    }

    @Override
    protected String consumerPrefix() {
        return AsyncTaskStreamConstants.RESUME_ANALYZE_CONSUMER_PREFIX;
    }

    @Override
    protected String threadName() {
        return "analyze-consumer";
    }

    /**
     * 解析消息载荷
     * 从消息中提取简历ID和分析内容
     */
    @Override
    protected AnalyzePayload parsePayload(AsyncMessageId messageId, Map<String, String> data) {
        String resumeIdStr = data.get(AsyncTaskStreamConstants.FIELD_RESUME_ID);
        String content = data.get(AsyncTaskStreamConstants.FIELD_CONTENT);
        if (resumeIdStr == null || content == null) {
            log.warn("无效的简历分析消息，已跳过: messageId={}", messageId);
            return null;
        }
        return new AnalyzePayload(Long.parseLong(resumeIdStr), content);
    }

    @Override
    protected String payloadIdentifier(AnalyzePayload payload) {
        return "resumeId=" + payload.resumeId();
    }

    /**
     * 标记任务为处理中
     */
    @Override
    protected void markProcessing(AnalyzePayload payload) {
        updateAnalyzeStatus(payload.resumeId(), AsyncTaskStatus.PROCESSING, null);
    }

    /**
     * 执行业务处理
     * 调用AI评分服务分析简历，处理过程中检查简历是否被删除
     */
    @Override
    protected void processBusiness(AnalyzePayload payload) {
        Long resumeId = payload.resumeId();
        // 处理前检查简历是否仍存在
        ResumeEntity existingResume = resumeRepository.findById(resumeId).orElse(null);
        if (existingResume == null) {
            log.warn("简历已删除，跳过分析任务: resumeId={}", resumeId);
            return;
        }

        // 调用AI评分服务
        ResumeAnalysisResponse analysis = gradingService.analyzeResume(
                existingResume.getUserId(),
                payload.content()
        );

        // 分析完成后再次检查简历是否存在，防止分析期间被删除
        ResumeEntity resume = resumeRepository.findById(resumeId).orElse(null);
        if (resume == null) {
            log.warn("分析期间简历被删除，跳过结果保存: resumeId={}", resumeId);
            return;
        }
        persistenceService.saveAnalysis(resume, analysis);
    }

    /**
     * 标记任务为已完成
     */
    @Override
    protected void markCompleted(AnalyzePayload payload) {
        updateAnalyzeStatus(payload.resumeId(), AsyncTaskStatus.COMPLETED, null);
    }

    /**
     * 标记任务为失败
     */
    @Override
    protected void markFailed(AnalyzePayload payload, String error) {
        updateAnalyzeStatus(payload.resumeId(), AsyncTaskStatus.FAILED, error);
    }

    /**
     * 重试消息
     * 将失败的任务重新发送到消息队列，附带重试次数
     */
    @Override
    protected void retryMessage(AnalyzePayload payload, int retryCount) {
        Long resumeId = payload.resumeId();
        String content = payload.content();
        try {
            Map<String, String> message = Map.of(
                    AsyncTaskStreamConstants.FIELD_RESUME_ID, resumeId.toString(),
                    AsyncTaskStreamConstants.FIELD_CONTENT, content,
                    AsyncTaskStreamConstants.FIELD_RETRY_COUNT, String.valueOf(retryCount)
            );

            queueService().sendTask(
                    AsyncTaskStreamConstants.RESUME_ANALYZE_STREAM_KEY,
                    message,
                    AsyncTaskStreamConstants.STREAM_MAX_LEN
            );
            log.info("简历分析任务已重新入队: resumeId={}, retryCount={}", resumeId, retryCount);
        } catch (Exception e) {
            log.error("重新入队简历分析任务失败: resumeId={}", resumeId, e);
            updateAnalyzeStatus(
                    resumeId,
                    AsyncTaskStatus.FAILED,
                    truncateError("重试入队失败: " + e.getMessage())
            );
        }
    }

    /**
     * 更新简历的分析状态
     */
    private void updateAnalyzeStatus(Long resumeId, AsyncTaskStatus status, String error) {
        try {
            resumeRepository.findById(resumeId).ifPresent(resume -> {
                resume.setAnalyzeStatus(status);
                resume.setAnalyzeError(error);
                resumeRepository.save(resume);
                log.debug("简历分析状态已更新: resumeId={}, status={}", resumeId, status);
            });
        } catch (Exception e) {
            log.error(
                    "更新简历分析状态失败: resumeId={}, status={}",
                    resumeId,
                    status,
                    e
            );
        }
    }
}