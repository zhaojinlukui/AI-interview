package interview.guide.modules.user.model;

/**
 * 登录或注册后的认证响应
 */
public record AuthResponse(
    String accessToken,
    String tokenType,
    long expiresAtEpochSecond,
    AuthUserDTO user
) {
}
