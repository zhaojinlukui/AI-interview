package interview.guide.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "app.async-task")
public class AsyncTaskProperties {

    private static final int DEFAULT_CONCURRENT_CONSUMERS = 1;
    private static final int DEFAULT_PREFETCH_COUNT = 10;

    private RabbitMqConfig rabbitmq = new RabbitMqConfig();

    public int rabbitMqConcurrentConsumers() {
        return positiveOrDefault(rabbitmq.getConcurrentConsumers(), DEFAULT_CONCURRENT_CONSUMERS);
    }

    public int rabbitMqMaxConcurrentConsumers() {
        int concurrentConsumers = rabbitMqConcurrentConsumers();
        int maxConcurrentConsumers =
                positiveOrDefault(rabbitmq.getMaxConcurrentConsumers(), concurrentConsumers);
        return Math.max(concurrentConsumers, maxConcurrentConsumers);
    }

    public int rabbitMqPrefetchCount() {
        return positiveOrDefault(rabbitmq.getPrefetchCount(), DEFAULT_PREFETCH_COUNT);
    }

    private int positiveOrDefault(int value, int defaultValue) {
        return value < 1 ? defaultValue : value;
    }

    @Data
    public static class RabbitMqConfig {

        private int concurrentConsumers = DEFAULT_CONCURRENT_CONSUMERS;
        private int maxConcurrentConsumers = DEFAULT_CONCURRENT_CONSUMERS;
        private int prefetchCount = DEFAULT_PREFETCH_COUNT;
    }
}
