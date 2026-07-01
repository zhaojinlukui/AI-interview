package interview.guide.common.async;

import interview.guide.common.constant.AsyncTaskStreamConstants;
import interview.guide.infrastructure.rabbitmq.RabbitMqTaskQueueService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("异步任务消费者模板")
class AbstractStreamConsumerTest {

    @Test
    @DisplayName("处理成功时更新处理和完成状态")
    void processMessageShouldMarkCompletedWhenBusinessSucceeds() {
        ConsumerFixture fixture = createFixture(false);

        fixture.processor.process(new AsyncMessageId("msg-1"), Map.of("id", "42"));

        assertThat(fixture.consumer.events).containsExactly("processing:42", "business:42", "completed:42");
        fixture.consumer.shutdown();
    }

    @Test
    @DisplayName("消息缺少必要字段时跳过处理")
    void processMessageShouldSkipMalformedPayload() {
        ConsumerFixture fixture = createFixture(false);

        fixture.processor.process(new AsyncMessageId("msg-1"), Map.of());

        assertThat(fixture.consumer.events).isEmpty();
        fixture.consumer.shutdown();
    }

    @Test
    @DisplayName("业务失败且未超过重试次数时重新入队")
    void processMessageShouldRetryWhenBusinessFailsBeforeRetryLimit() {
        ConsumerFixture fixture = createFixture(true);

        fixture.processor.process(new AsyncMessageId("msg-1"), Map.of(
            "id", "42",
            AsyncTaskStreamConstants.FIELD_RETRY_COUNT, "1"
        ));

        assertThat(fixture.consumer.events).containsExactly("processing:42", "business:42", "retry:42:2");
        fixture.consumer.shutdown();
    }

    @Test
    @DisplayName("业务失败且达到重试次数时标记失败")
    void processMessageShouldMarkFailedWhenRetryLimitReached() {
        ConsumerFixture fixture = createFixture(true);

        fixture.processor.process(new AsyncMessageId("msg-1"), Map.of(
            "id", "42",
            AsyncTaskStreamConstants.FIELD_RETRY_COUNT,
            String.valueOf(AsyncTaskStreamConstants.MAX_RETRY_COUNT)
        ));

        assertThat(fixture.consumer.events)
            .containsExactly("processing:42", "business:42", "failed:42");
        fixture.consumer.shutdown();
    }

    private ConsumerFixture createFixture(boolean failBusiness) {
        RabbitMqTaskQueueService queueService = mock(RabbitMqTaskQueueService.class);
        SimpleMessageListenerContainer container = mock(SimpleMessageListenerContainer.class);
        ArgumentCaptor<RabbitMqTaskQueueService.TaskMessageProcessor> processorCaptor =
            ArgumentCaptor.forClass(RabbitMqTaskQueueService.TaskMessageProcessor.class);
        CountDownLatch startLatch = new CountDownLatch(1);

        when(queueService.startConsumer(
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            processorCaptor.capture()
        )).thenAnswer(invocation -> {
            startLatch.countDown();
            return container;
        });

        TestConsumer consumer = new TestConsumer(queueService, failBusiness);
        consumer.init();
        try {
            assertThat(startLatch.await(5, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
        return new ConsumerFixture(consumer, processorCaptor.getValue());
    }

    private record ConsumerFixture(
        TestConsumer consumer,
        RabbitMqTaskQueueService.TaskMessageProcessor processor
    ) {}

    private static class TestConsumer extends AbstractStreamConsumer<TestPayload> {

        private final boolean failBusiness;
        private final List<String> events = new ArrayList<>();

        TestConsumer(RabbitMqTaskQueueService queueService, boolean failBusiness) {
            super(queueService);
            this.failBusiness = failBusiness;
        }

        @Override
        protected String taskDisplayName() {
            return "测试";
        }

        @Override
        protected String streamKey() {
            return "test.routing";
        }

        @Override
        protected String groupName() {
            return "test.queue";
        }

        @Override
        protected String consumerPrefix() {
            return "test-consumer-";
        }

        @Override
        protected String threadName() {
            return "test-consumer";
        }

        @Override
        protected TestPayload parsePayload(AsyncMessageId messageId, Map<String, String> data) {
            String id = data.get("id");
            return id == null ? null : new TestPayload(id);
        }

        @Override
        protected String payloadIdentifier(TestPayload payload) {
            return "id=" + payload.id();
        }

        @Override
        protected void markProcessing(TestPayload payload) {
            events.add("processing:" + payload.id());
        }

        @Override
        protected void processBusiness(TestPayload payload) {
            events.add("business:" + payload.id());
            if (failBusiness) {
                throw new IllegalStateException("boom");
            }
        }

        @Override
        protected void markCompleted(TestPayload payload) {
            events.add("completed:" + payload.id());
        }

        @Override
        protected void markFailed(TestPayload payload, String error) {
            events.add("failed:" + payload.id());
        }

        @Override
        protected void retryMessage(TestPayload payload, int retryCount) {
            events.add("retry:" + payload.id() + ":" + retryCount);
        }
    }

    private record TestPayload(String id) {}
}
