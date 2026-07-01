package interview.guide.modules.user.model;

import java.time.LocalDateTime;

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
