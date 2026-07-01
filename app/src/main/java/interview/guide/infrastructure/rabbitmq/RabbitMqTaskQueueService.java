package interview.guide.infrastructure.rabbitmq;

import com.rabbitmq.client.Channel;
import interview.guide.common.async.AsyncMessageId;
import interview.guide.common.config.AsyncTaskProperties;
import interview.guide.common.constant.AsyncTaskStreamConstants;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.amqp.rabbit.listener.api.ChannelAwareMessageListener;
import org.springframework.data.redis.listener.Topic;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * RabbitMQ 异步任务队列服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RabbitMqTaskQueueService {

    // 任务交换机
    private static final TopicExchange TASK_EXCHANGE = new TopicExchange(
            AsyncTaskStreamConstants.RABBITMQ_EXCHANGE_NAME,
            true,   // 持久化
            false
    );

    // RabbitMQ 模板，用于发送消息
    private final RabbitTemplate rabbitTemplate;
    // RabbitMQ 管理接口，用于声明交换机、队列和绑定
    private final AmqpAdmin amqpAdmin;
    // 连接工厂，用于创建消费者连接
    private final ConnectionFactory connectionFactory;
    // JSON 序列化/反序列化工具
    private final ObjectMapper objectMapper;
    // 异步任务配置属性
    private final AsyncTaskProperties asyncTaskProperties;

    /**
     * 任务消息处理器函数式接口
     * 用于定义如何处理接收到的异步任务消息
     */
    @FunctionalInterface
    public interface TaskMessageProcessor {
        void process(AsyncMessageId messageId, Map<String, String> data);
    }

    /**
     * 发送异步任务消息
     *
     * 生成唯一消息ID，将消息内容序列化为JSON后发送到指定路由键。
     * 消息设置为持久化模式，防止服务器重启丢失。
     *
     * @return 生成的消息ID
     */
    public String sendTask(String routingKey, Map<String, String> message, int maxLen) {
        // 生成唯一的消息ID
        String messageId = UUID.randomUUID().toString();
        try {
            // 确保交换机已声明
            declareExchange();

            // 将消息内容序列化为 JSON 字符串
            String body = objectMapper.writeValueAsString(message);

            // 设置消息属性
            MessageProperties properties = new MessageProperties();
            properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);  // 内容类型为 JSON
            properties.setContentEncoding(StandardCharsets.UTF_8.name());    // 编码格式为 UTF-8
            properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);      // 消息持久化
            properties.setMessageId(messageId);                              // 设置消息ID

            // 发送消息到指定的交换机和路由键
            rabbitTemplate.send(
                    AsyncTaskStreamConstants.RABBITMQ_EXCHANGE_NAME,
                    routingKey,
                    new Message(body.getBytes(StandardCharsets.UTF_8), properties)
            );

            log.debug("Sent RabbitMQ async task: routingKey={}, messageId={}, maxLen={}",
                    routingKey, messageId, maxLen);
            return messageId;
        } catch (Exception e) {
            log.error("Failed to send RabbitMQ async task: routingKey={}, messageId={}",
                    routingKey, messageId, e);
            throw new IllegalStateException("Failed to send RabbitMQ async task", e);
        }
    }

    /**
     * 启动消费者监听器
     *
     * 创建并启动一个消息监听容器，开始消费指定队列的消息。
     *
     * @return 消息监听容器，可用于后续停止消费者
     */
    public SimpleMessageListenerContainer startConsumer(
            String routingKey,
            String queueName,
            String consumerName,
            String containerName,
            TaskMessageProcessor processor
    ) {
        SimpleMessageListenerContainer container = createConsumerContainer(
                routingKey,
                queueName,
                consumerName,
                containerName,
                processor
        );
        container.start();
        return container;
    }

    /**
     * 创建消费者容器
     *
     * 配置说明：
     * - 手动确认模式：需要消费者显式确认消息
     * - 并发消费者：支持多线程处理消息
     * - 预取计数：控制每个消费者一次能获取多少条消息
     *
     * @return 配置好的消息监听容器（未启动）
     */
    SimpleMessageListenerContainer createConsumerContainer(
            String routingKey,
            String queueName,
            String consumerName,
            String containerName,
            TaskMessageProcessor processor
    ) {
        // 声明队列和绑定关系
        declareQueueBinding(routingKey, queueName);

        // 创建消息监听容器
        SimpleMessageListenerContainer container =
                new SimpleMessageListenerContainer(connectionFactory);
        container.setBeanName(containerName);
        container.setQueueNames(queueName);
        // 设置为手动确认模式，消费者处理完消息后需要手动确认
        container.setAcknowledgeMode(AcknowledgeMode.MANUAL);

        // 从配置中获取并发消费者相关参数
        int concurrentConsumers = asyncTaskProperties.rabbitMqConcurrentConsumers();  // 初始并发消费者数量
        int maxConcurrentConsumers = asyncTaskProperties.rabbitMqMaxConcurrentConsumers();  // 最大并发消费者数量
        int prefetchCount = asyncTaskProperties.rabbitMqPrefetchCount();  // 每个消费者预取的消息数量
        container.setPrefetchCount(prefetchCount);
        container.setConcurrentConsumers(concurrentConsumers);
        container.setMaxConcurrentConsumers(maxConcurrentConsumers);

        // 设置消费者标签策略，用于在管理界面中区分不同的消费者
        AtomicInteger tagSequence = new AtomicInteger();
        container.setConsumerTagStrategy(queue ->
                consumerName + "-" + tagSequence.incrementAndGet()
        );

        // 设置消息监听器，处理接收到的消息
        container.setMessageListener((ChannelAwareMessageListener) (message, channel) ->
                processMessage(queueName, processor, message, channel)
        );

        log.info(
                "RabbitMQ async consumer configured: queue={}, consumerName={}, "
                        + "concurrentConsumers={}, maxConcurrentConsumers={}, prefetchCount={}",
                queueName,
                consumerName,
                concurrentConsumers,
                maxConcurrentConsumers,
                prefetchCount
        );
        return container;
    }

    /**
     * 处理接收到的消息
     *
     * 处理流程：
     * 1. 获取 deliveryTag 用于消息确认
     * 2. 解析消息ID
     * 3. 反序列化消息内容
     * 4. 调用处理器处理消息
     * 5. 无论处理成功或失败都确认消息（避免死循环）
     */
    private void processMessage(
            String queueName,
            TaskMessageProcessor processor,
            Message message,
            Channel channel
    ) throws Exception {
        // 获取 deliveryTag，用于消息确认
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        // 解析消息ID
        AsyncMessageId messageId = resolveMessageId(message, deliveryTag);

        try {
            // 反序列化消息体为 Map<String, String>
            Map<String, String> data = objectMapper.readValue(
                    new String(message.getBody(), StandardCharsets.UTF_8),
                    new TypeReference<>() {}
            );
            // 调用处理器处理消息
            processor.process(messageId, data);
        } catch (Exception e) {
            // 记录处理失败日志，但不重新抛出异常
            log.error("RabbitMQ async task processing failed unexpectedly: queue={}, messageId={}",
                    queueName, messageId, e);
        } finally {
            // 无论处理成功或失败，都确认消息，避免重复消费
            // multiple=false: 只确认当前消息
            channel.basicAck(deliveryTag, false);
        }
    }

    /**
     * 解析消息ID
     *
     * 优先使用消息属性中的 messageId，若为空则使用 deliveryTag 作为后备。
     */
    private AsyncMessageId resolveMessageId(Message message, long deliveryTag) {
        String messageId = message.getMessageProperties().getMessageId();
        if (messageId == null || messageId.isBlank()) {
            messageId = String.valueOf(deliveryTag);
        }
        return new AsyncMessageId(messageId);
    }

    /**
     * 声明队列和绑定关系。
     */
    private void declareQueueBinding(String routingKey, String queueName) {
        // 确保交换机存在
        declareExchange();

        // 声明持久化队列
        Queue queue = new Queue(queueName, true);
        amqpAdmin.declareQueue(queue);

        // 将队列绑定到交换机，使用指定的路由键
        Binding binding = BindingBuilder.bind(queue)
                .to(TASK_EXCHANGE)
                .with(routingKey);
        amqpAdmin.declareBinding(binding);

        log.info("RabbitMQ async queue is ready: exchange={}, queue={}, routingKey={}",
                AsyncTaskStreamConstants.RABBITMQ_EXCHANGE_NAME, queueName, routingKey);
    }

    /**
     * 声明交换机
     */
    private void declareExchange() {
        amqpAdmin.declareExchange(TASK_EXCHANGE);
    }
}