package interview.guide.common.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("异步任务配置")
class AsyncTaskPropertiesTest {

  @Test
  @DisplayName("默认配置保持单 worker 消费")
  void defaultValuesShouldKeepSingleWorkerConsumption() {
    AsyncTaskProperties properties = new AsyncTaskProperties();

    assertThat(properties.rabbitMqConcurrentConsumers()).isEqualTo(1);
    assertThat(properties.rabbitMqMaxConcurrentConsumers()).isEqualTo(1);
    assertThat(properties.rabbitMqPrefetchCount()).isEqualTo(10);
  }

  @Test
  @DisplayName("小于 1 的配置回退到安全默认值")
  void valuesBelowOneShouldFallbackToSafeDefaults() {
    AsyncTaskProperties properties = new AsyncTaskProperties();
    properties.getRabbitmq().setConcurrentConsumers(0);
    properties.getRabbitmq().setMaxConcurrentConsumers(-1);
    properties.getRabbitmq().setPrefetchCount(0);

    assertThat(properties.rabbitMqConcurrentConsumers()).isEqualTo(1);
    assertThat(properties.rabbitMqMaxConcurrentConsumers()).isEqualTo(1);
    assertThat(properties.rabbitMqPrefetchCount()).isEqualTo(10);
  }

  @Test
  @DisplayName("最大并发小于基础并发时自动提升")
  void maxConcurrentConsumersShouldNotBeBelowConcurrentConsumers() {
    AsyncTaskProperties properties = new AsyncTaskProperties();
    properties.getRabbitmq().setConcurrentConsumers(4);
    properties.getRabbitmq().setMaxConcurrentConsumers(2);

    assertThat(properties.rabbitMqConcurrentConsumers()).isEqualTo(4);
    assertThat(properties.rabbitMqMaxConcurrentConsumers()).isEqualTo(4);
  }
}
