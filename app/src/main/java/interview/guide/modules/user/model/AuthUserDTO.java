package interview.guide.modules.user.model;

import java.time.LocalDateTime;

/**
 * 当前登录用户的基础信息
 */
public record AuthUserDTO(
    Long id,
    String username,
    String displayName,
    UserRole role,
    LocalDateTime lastLoginAt
) {
}
