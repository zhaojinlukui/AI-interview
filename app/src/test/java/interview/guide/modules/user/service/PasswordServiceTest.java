package interview.guide.modules.user.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("密码哈希服务")
class PasswordServiceTest {

  private PasswordService passwordService;

  @BeforeEach
  void setUp() {
    passwordService = new PasswordService();
  }

  @Nested
  @DisplayName("BCrypt 哈希")
  class BCryptHash {

    @Test
    @DisplayName("同一明文多次哈希结果不同")
    void shouldUseRandomSaltForEachHash() {
      String first = passwordService.hash("secure-password");
      String second = passwordService.hash("secure-password");

      assertThat(first).isNotEqualTo(second);
      assertThat(first).startsWith("$2");
      assertThat(second).startsWith("$2");
    }

    @Test
    @DisplayName("哈希不等于明文且可匹配正确密码")
    void shouldMatchCorrectPassword() {
      String encoded = passwordService.hash("secure-password");

      assertThat(encoded).isNotEqualTo("secure-password");
      assertThat(passwordService.matches("secure-password", encoded)).isTrue();
    }
  }

  @Nested
  @DisplayName("密码校验")
  class PasswordMatching {

    @Test
    @DisplayName("错误密码返回 false")
    void shouldRejectWrongPassword() {
      String encoded = passwordService.hash("secure-password");

      assertThat(passwordService.matches("wrong-password", encoded)).isFalse();
    }

    @Test
    @DisplayName("空值和非法格式返回 false")
    void shouldRejectBlankOrInvalidEncodedValues() {
      assertThat(passwordService.matches("secure-password", null)).isFalse();
      assertThat(passwordService.matches("secure-password", "")).isFalse();
      assertThat(passwordService.matches("secure-password", "not-a-bcrypt-hash")).isFalse();
    }

    @Test
    @DisplayName("旧 PBKDF2 格式返回 false")
    void shouldRejectLegacyPbkdf2Hash() {
      String legacyHash = "pbkdf2_sha256$120000$c2FsdA==$aGFzaA==";

      assertThat(passwordService.matches("secure-password", legacyHash)).isFalse();
    }
  }
}
