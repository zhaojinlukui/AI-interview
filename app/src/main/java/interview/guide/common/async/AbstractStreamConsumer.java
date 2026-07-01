package interview.guide.common.async;

import interview.guide.common.constant.AsyncTaskStreamConstants;
import interview.guide.infrastructure.rabbitmq.RabbitMqTaskQueueService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;

/**
 * 抽象消息流消费者
 *
 * 提供基于 RabbitMQ 的异步任务消费通用实现，子类只需实现业务相关的抽象方法。
 *
 * 核心功能：
 * 1. 管理消费者生命周期（启动、关闭）
 * 2. 自动重连机制（启动失败时重试）
 * 3. 消息解析与业务处理分发
 * 4. 失败重试策略（最多重试 MAX_RETRY_COUNT 次）
 *
 * @param <T> 业务载荷类型
 */
@Slf4j
@RequiredArgsConstructor(access = AccessLevel.PROTECTED)
public abstract class AbstractStreamConsumer<T> {

    // RabbitMQ 队列服务，用于创建消费者
    private final RabbitMqTaskQueueService queueService;
    // 消费者运行状态标识
    private final AtomicBoolean running = new AtomicBoolean(false);
    // 消息监听容器，用于控制消费者的启停
    private SimpleMessageListenerContainer listenerContainer;
    // 单线程执行器，用于启动消费者循环
    private ExecutorService executorService;
    // 消费者名称，由前缀和随机ID组成，用于标识不同的消费者实例
    private String consumerName;

    /**
     * 初始化消费者
     *
     * 在 Bean 构造完成后自动调用，启动消费循环。
     * 使用单线程守护线程池执行消费者启动逻辑。
     */
    @PostConstruct
    public void init() {
        // 生成消费者名称：前缀 + 8位随机UUID
        this.consumerName = consumerPrefix() + UUID.randomUUID().toString().substring(0, 8);
        // 创建单线程守护线程池
        this.executorService = new ThreadPoolExecutor(
                1,
                1,
                0L,
                TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(),
                r -> {
                    Thread thread = new Thread(r, threadName());
                    thread.setDaemon(true);  // 守护线程，主线程退出时自动销毁
                    return thread;
                }
        );
        running.set(true);
        // 提交消费者启动任务
        executorService.submit(this::startConsumerLoop);
        log.info("{} consumer scheduled: consumerName={}", taskDisplayName(), consumerName);
    }

    /**
     * 关闭消费者
     *
     * 在 Bean 销毁前自动调用，停止消息监听并关闭线程池。
     */
    @PreDestroy
    public void shutdown() {
        running.set(false);
        // 停止消息监听容器
        if (listenerContainer != null) {
            listenerContainer.stop();
        }
        // 立即关闭线程池，中断正在执行的任务
        if (executorService != null) {
            executorService.shutdownNow();
        }
        log.info("{} consumer stopped: consumerName={}", taskDisplayName(), consumerName);
    }

    /**
     * 启动消费者循环
     *
     * 循环尝试启动消费者，直到成功或收到关闭信号。
     * 启动失败时等待 5 秒后重试，避免频繁重连。
     */
    private void startConsumerLoop() {
        while (running.get()) {
            try {
                // 尝试启动消费者
                this.listenerContainer = queueService.startConsumer(
                        streamKey(),
                        groupName(),
                        consumerName,
                        threadName(),
                        this::processMessage
                );
                log.info("{} consumer started: consumerName={}", taskDisplayName(), consumerName);
                return;  // 启动成功，退出循环
            } catch (Exception e) {
                // 如果已收到关闭信号，不再重试
                if (!running.get()) {
                    return;
                }
                log.warn("{} consumer start failed, will retry: consumerName={}",
                        taskDisplayName(), consumerName, e);
                try {
                    // 等待 5 秒后重试，避免频繁重连
                    TimeUnit.SECONDS.sleep(5);
                } catch (InterruptedException interruptedException) {
                    // 被中断时恢复中断状态并退出
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    /**
     * 处理接收到的消息
     *
     * 完整处理流程：
     * 1. 解析消息载荷
     * 2. 标记为处理中
     * 3. 执行业务逻辑
     * 4. 标记为已完成
     * 5. 若处理失败且未超过最大重试次数，则发送重试消息
     * 6. 若超过最大重试次数，则标记为最终失败
     */
    private void processMessage(AsyncMessageId messageId, Map<String, String> data) {
        T payload;
        try {
            // 解析消息载荷
            payload = parsePayload(messageId, data);
        } catch (Exception e) {
            log.warn("{} task payload parse failed: messageId={}", taskDisplayName(), messageId, e);
            return;
        }

        // 载荷为空则跳过处理
        if (payload == null) {
            return;
        }

        // 解析重试次数，默认为 0
        int retryCount = parseRetryCount(data);
        log.info("Processing {} task: payload={}, messageId={}, retryCount={}",
                taskDisplayName(), payloadIdentifier(payload), messageId, retryCount);

        try {
            // 标记为处理中
            markProcessing(payload);
            // 执行业务逻辑
            processBusiness(payload);
            // 标记为已完成
            markCompleted(payload);
            log.info("{} task completed: {}", taskDisplayName(), payloadIdentifier(payload));
        } catch (Exception e) {
            log.error("{} task failed: {}", taskDisplayName(), payloadIdentifier(payload), e);
            // 判断是否需要重试
            if (retryCount < AsyncTaskStreamConstants.MAX_RETRY_COUNT) {
                // 未超过最大重试次数，发送重试消息（重试次数 +1）
                retryMessage(payload, retryCount + 1);
            } else {
                // 超过最大重试次数，标记为最终失败
                markFailed(payload, truncateError(
                        taskDisplayName() + " failed after retry " + retryCount + ": " + e.getMessage()
                ));
            }
        }
    }

    /**
     * 解析重试次数
     *
     * 从消息数据中获取重试次数字段，默认为 0。
     * 解析失败时返回 0，避免异常中断处理流程。
     */
    protected int parseRetryCount(Map<String, String> data) {
        try {
            return Integer.parseInt(data.getOrDefault(
                    AsyncTaskStreamConstants.FIELD_RETRY_COUNT,
                    "0"
            ));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * 截断错误信息
     *
     * 限制错误信息长度不超过 500 字符，防止数据库字段溢出。
     */
    protected String truncateError(String error) {
        if (error == null) {
            return null;
        }
        return error.length() > 500 ? error.substring(0, 500) : error;
    }

    /**
     * 获取队列服务实例，供子类使用（如发送重试消息）。
     */
    protected RabbitMqTaskQueueService queueService() {
        return queueService;
    }

    // ==================== 子类必须实现的抽象方法 ====================

    /** 任务显示名称，用于日志标识 */
    protected abstract String taskDisplayName();

    /** RabbitMQ 路由键，用于将消息路由到指定队列 */
    protected abstract String streamKey();

    /** RabbitMQ 队列名称，即消费者组名 */
    protected abstract String groupName();

    /** 消费者名称前缀，用于区分不同类型的消费者 */
    protected abstract String consumerPrefix();

    /** 消费者线程名称 */
    protected abstract String threadName();

    /** 将消息数据解析为业务载荷对象 */
    protected abstract T parsePayload(AsyncMessageId messageId, Map<String, String> data);

    /** 获取载荷的唯一标识，用于日志输出 */
    protected abstract String payloadIdentifier(T payload);

    /** 标记载荷为处理中状态 */
    protected abstract void markProcessing(T payload);

    /** 执行实际业务逻辑 */
    protected abstract void processBusiness(T payload);

    /** 标记载荷为已完成状态 */
    protected abstract void markCompleted(T payload);

    /** 标记载荷为失败状态，记录错误信息 */
    protected abstract void markFailed(T payload, String error);

    /** 发送重试消息，retryCount 为新的重试次数 */
    protected abstract void retryMessage(T payload, int retryCount);
}