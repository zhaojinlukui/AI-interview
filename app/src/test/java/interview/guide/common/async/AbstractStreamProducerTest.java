package interview.guide.common.async;

import interview.guide.infrastructure.rabbitmq.RabbitMqTaskQueueService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("异步任务生产者模板")
class AbstractStreamProducerTest {

    @Test
    @DisplayName("发送失败时调用失败回调")
    void sendTaskShouldCallFailureCallbackWhenQueueSendFails() {
        RabbitMqTaskQueueService queueService = mock(RabbitMqTaskQueueService.class);
        when(queueService.sendTask(eq("test.routing"), any(), anyInt()))
            .thenThrow(new IllegalStateException("rabbit down"));
        TestProducer producer = new TestProducer(queueService);

        producer.send("payload-1");

        assertThat(producer.failedPayload).isEqualTo("payload-1");
        assertThat(producer.failedError).contains("rabbit down");
    }

    private static class TestProducer extends AbstractStreamProducer<String> {

        private String failedPayload;
        private String failedError;

        TestProducer(RabbitMqTaskQueueService queueService) {
            super(queueService);
        }

        void send(String payload) {
            sendTask(payload);
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
        protected Map<String, String> buildMessage(String payload) {
            return Map.of("payload", payload);
        }

        @Override
        protected String payloadIdentifier(String payload) {
            return payload;
        }

        @Override
        protected void onSendFailed(String payload, String error) {
            this.failedPayload = payload;
            this.failedError = error;
        }
    }
}
