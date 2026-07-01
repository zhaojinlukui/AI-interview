package interview.guide.infrastructure.rabbitmq;

import interview.guide.common.config.AsyncTaskProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.support.ConsumerTagStrategy;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@DisplayName("RabbitMQ 异步任务队列服务")
class RabbitMqTaskQueueServiceTest {

  @Test
  @DisplayName("创建消费者容器时应用并发和预取配置")
  void createConsumerContainerShouldApplyConcurrencyAndPrefetchConfig() {
    AsyncTaskProperties properties = new AsyncTaskProperties();
    properties.getRabbitmq().setConcurrentConsumers(3);
    properties.getRabbitmq().setMaxConcurrentConsumers(5);
    properties.getRabbitmq().setPrefetchCount(7);
    RabbitMqTaskQueueService service = newService(properties);

    SimpleMessageListenerContainer container = service.createConsumerContainer(
        "test.routing",
        "test.queue",
        "test-consumer",
        "test-container",
        (messageId, data) -> { }
    );

    assertThat(ReflectionTestUtils.getField(container, "concurrentConsumers")).isEqualTo(3);
    assertThat(ReflectionTestUtils.getField(container, "maxConcurrentConsumers")).isEqualTo(5);
    assertThat(ReflectionTestUtils.getField(container, "prefetchCount")).isEqualTo(7);
    assertThat(ReflectionTestUtils.getField(container, "acknowledgeMode"))
        .isEqualTo(AcknowledgeMode.MANUAL);
  }

  @Test
  @DisplayName("消费者标签按内部 worker 生成唯一序号")
  void createConsumerContainerShouldGenerateUniqueConsumerTags() {
    RabbitMqTaskQueueService service = newService(new AsyncTaskProperties());

    SimpleMessageListenerContainer container = service.createConsumerContainer(
        "test.routing",
        "test.queue",
        "test-consumer",
        "test-container",
        (messageId, data) -> { }
    );

    ConsumerTagStrategy tagStrategy =
        (ConsumerTagStrategy) ReflectionTestUtils.getField(container, "consumerTagStrategy");

    assertThat(tagStrategy).isNotNull();
    assertThat(tagStrategy.createConsumerTag("test.queue")).isEqualTo("test-consumer-1");
    assertThat(tagStrategy.createConsumerTag("test.queue")).isEqualTo("test-consumer-2");
  }

  private RabbitMqTaskQueueService newService(AsyncTaskProperties properties) {
    return new RabbitMqTaskQueueService(
        mock(RabbitTemplate.class),
        mock(AmqpAdmin.class),
        mock(ConnectionFactory.class),
        new ObjectMapper(),
        properties
    );
  }
}
