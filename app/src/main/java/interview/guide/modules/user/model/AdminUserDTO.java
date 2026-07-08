package interview.guide.modules.user.model;

import java.time.LocalDateTime;

/**
 * 管理端用户列表展示数据
 */
public record AdminUserDTO(
    Long id,
    String username,
    String displayName,
    UserRole role,
    Boolean enabled,
    LocalDateTime createdAt,
    LocalDateTime updatedAt,
    LocalDateTime lastLoginAt,
    long resumeCount,
    long textInterviewCount,
    long voiceInterviewCount,
    LocalDateTime recentActivityAt
) {
}
