package interview.guide.common.auth;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 认证与内置管理员配置。
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.auth")
public class AuthProperties {

  /**
   * HMAC token 签名密钥。生产环境必须通过环境变量覆盖。
   */
  private String tokenSecret = "dev-only-change-me-at-least-32-characters";

  /**
   * 访问令牌有效期，单位分钟。
   */
  private long accessTokenTtlMinutes = 1440;

  private Admin admin = new Admin();

  private LegacyOwner legacyOwner = new LegacyOwner();

  @Data
  public static class Admin {

    private String username = "admin";

    private String password = "123456";

    private String displayName = "系统管理员";

    /**
     * 是否在每次启动时把配置中的管理员密码写回数据库。
     */
    private boolean resetPasswordOnStartup = false;
  }

  @Data
  public static class LegacyOwner {

    private String username = "zhaojin";

    private String password = "123456";

    private String displayName = "zhaojin";
  }
}
