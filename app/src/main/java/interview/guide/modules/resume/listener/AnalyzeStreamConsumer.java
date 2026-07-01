package interview.guide.modules.resume.listener;

import interview.guide.common.async.AsyncMessageId;
import interview.guide.common.async.AbstractStreamConsumer;
import interview.guide.common.constant.AsyncTaskStreamConstants;
import interview.guide.common.model.AsyncTaskStatus;
import interview.guide.infrastructure.rabbitmq.RabbitMqTaskQueueService;
import interview.guide.modules.interview.model.ResumeAnalysisResponse;
import interview.guide.modules.resume.model.ResumeEntity;
import interview.guide.modules.resume.repository.ResumeRepository;
import interview.guide.modules.resume.service.ResumeGradingService;
import interview.guide.modules.resume.service.ResumePersistenceService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 简历分析 RabbitMQ 消费者
 */
@Slf4j
@Component
public class AnalyzeStreamConsumer extends AbstractStreamConsumer<AnalyzeStreamConsumer.AnalyzePayload> {

    // 简历评分服务
    private final ResumeGradingService gradingService;
    // 简历持久化服务
    private final ResumePersistenceService persistenceService;
    // 简历数据访问层
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

    // 简历分析任务载荷
    record AnalyzePayload(Long resumeId, String content) {}

    // 获取任务的显示名称
    @Override
    protected String taskDisplayName() {
        return "简历分析";
    }

    // 获取消息队列的 Stream 键值（路由键）
    @Override
    protected String streamKey() {
        return AsyncTaskStreamConstants.RESUME_ANALYZE_STREAM_KEY;
    }

    // 获取消费者组名
    @Override
    protected String groupName() {
        return AsyncTaskStreamConstants.RESUME_ANALYZE_GROUP_NAME;
    }

    // 获取消费者名称前缀
    @Override
    protected String consumerPrefix() {
        return AsyncTaskStreamConstants.RESUME_ANALYZE_CONSUMER_PREFIX;
    }

    // 获取处理线程名称
    @Override
    protected String threadName() {
        return "analyze-consumer";
    }

    /**
     * 解析消息载荷
     * 将 Map 格式的消息数据转换为 AnalyzePayload 对象
     */
    @Override
    protected AnalyzePayload parsePayload(AsyncMessageId messageId, Map<String, String> data) {
        // 从消息中提取字段
        String resumeIdStr = data.get(AsyncTaskStreamConstants.FIELD_RESUME_ID);
        String content = data.get(AsyncTaskStreamConstants.FIELD_CONTENT);

        // 验证必要字段
        if (resumeIdStr == null || content == null) {
            log.warn("消息格式错误，跳过: messageId={}", messageId);
            return null;  // 返回 null，父类会丢弃此消息
        }

        // 创建任务载荷对象
        return new AnalyzePayload(Long.parseLong(resumeIdStr), content);
    }

    // 获取任务载荷的标识符
    @Override
    protected String payloadIdentifier(AnalyzePayload payload) {
        return "resumeId=" + payload.resumeId();
    }

    // 标记任务为处理中
    @Override
    protected void markProcessing(AnalyzePayload payload) {
        // 更新简历的分析状态为 PROCESSING，清除之前的错误信息
        updateAnalyzeStatus(payload.resumeId(), AsyncTaskStatus.PROCESSING, null);
    }

    /**
     * 执行简历分析的主要流程
     */
    @Override
    protected void processBusiness(AnalyzePayload payload) {
        Long resumeId = payload.resumeId();

        // 第一步：检查简历是否还存在
        if (!resumeRepository.existsById(resumeId)) {
            log.warn("简历已被删除，跳过分析任务: resumeId={}", resumeId);
            return;  // 简历不存在，直接返回（消息会被确认，不会重试）
        }

        // 第二步：调用 AI 服务进行简历分析
        // gradingService.analyzeResume() 可能会调用外部 AI API
        ResumeAnalysisResponse analysis = gradingService.analyzeResume(payload.content());

        // 第三步：再次检查简历是否存在（分析过程可能耗时较长）
        ResumeEntity resume = resumeRepository.findById(resumeId).orElse(null);
        if (resume == null) {
            log.warn("简历在分析期间被删除，跳过保存结果: resumeId={}", resumeId);
            return;  // 简历已被删除，不保存结果
        }

        // 第四步：保存分析结果到数据库
        // persistenceService.saveAnalysis() 会将评分、建议等信息保存到相关表
        persistenceService.saveAnalysis(resume, analysis);
    }

    // 标记任务为完成
    @Override
    protected void markCompleted(AnalyzePayload payload) {
        // 更新简历的分析状态为 COMPLETED
        updateAnalyzeStatus(payload.resumeId(), AsyncTaskStatus.COMPLETED, null);
    }

    // 标记任务为失败
    @Override
    protected void markFailed(AnalyzePayload payload, String error) {
        // 更新简历的分析状态为 FAILED，并记录失败原因
        updateAnalyzeStatus(payload.resumeId(), AsyncTaskStatus.FAILED, error);
    }

    // 重新发送任务到队列进行重试
    @Override
    protected void retryMessage(AnalyzePayload payload, int retryCount) {
        Long resumeId = payload.resumeId();
        String content = payload.content();
        try {
            // 构建重试消息，包含更新后的重试次数
            Map<String, String> message = Map.of(
                    AsyncTaskStreamConstants.FIELD_RESUME_ID, resumeId.toString(),
                    AsyncTaskStreamConstants.FIELD_CONTENT, content,
                    AsyncTaskStreamConstants.FIELD_RETRY_COUNT, String.valueOf(retryCount)  // 更新重试次数
            );

            // 发送重试消息到队列
            queueService().sendTask(
                    AsyncTaskStreamConstants.RESUME_ANALYZE_STREAM_KEY,  // 使用同一个路由键
                    message,
                    AsyncTaskStreamConstants.STREAM_MAX_LEN
            );
            log.info("简历分析任务已重新入队: resumeId={}, retryCount={}", resumeId, retryCount);

        } catch (Exception e) {
            // 重试入队失败，记录错误并标记为最终失败
            log.error("重试入队失败: resumeId={}, error={}", resumeId, e.getMessage(), e);
            updateAnalyzeStatus(
                    resumeId,
                    AsyncTaskStatus.FAILED,
                    truncateError("重试入队失败: " + e.getMessage())
            );
        }
    }

    // 更新简历的分析状态
    private void updateAnalyzeStatus(Long resumeId, AsyncTaskStatus status, String error) {
        try {
            // 查找简历并更新状态
            resumeRepository.findById(resumeId).ifPresent(resume -> {
                resume.setAnalyzeStatus(status);      // 设置分析状态
                resume.setAnalyzeError(error);         // 设置错误信息（可能为null）
                resumeRepository.save(resume);         // 保存到数据库
                log.debug("分析状态已更新: resumeId={}, status={}", resumeId, status);
            });
        } catch (Exception e) {
            // 状态更新失败，记录错误但不抛出异常
            // 防止影响消息的确认处理
            log.error("更新分析状态失败: resumeId={}, status={}, error={}",
                    resumeId, status, e.getMessage(), e);
        }
    }
}