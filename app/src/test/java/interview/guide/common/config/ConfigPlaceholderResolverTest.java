package interview.guide.common.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayName("ConfigPlaceholderResolver 测试")
class ConfigPlaceholderResolverTest {

  @AfterEach
  void tearDown() {
    System.clearProperty("TEST_API_KEY");
    System.clearProperty("TEST_DEFAULT_MODEL");
  }

  @Test
  @DisplayName("优先解析系统属性中的占位符值")
  void resolveSystemPropertyPlaceholder() {
    System.setProperty("TEST_API_KEY", "sk-runtime-secret");

    assertEquals("sk-runtime-secret", ConfigPlaceholderResolver.resolve("${TEST_API_KEY}"));
  }

  @Test
  @DisplayName("占位符缺失时回退到默认值")
  void resolvePlaceholderDefaultValue() {
    assertEquals("qwen-turbo",
        ConfigPlaceholderResolver.resolve("${TEST_DEFAULT_MODEL:qwen-turbo}"));
  }

  @Test
  @DisplayName("普通字符串保持原样")
  void keepPlainValueUntouched() {
    assertEquals("plain-text", ConfigPlaceholderResolver.resolve("plain-text"));
  }
}
