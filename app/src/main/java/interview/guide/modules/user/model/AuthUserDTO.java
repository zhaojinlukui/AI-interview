package interview.guide.modules.user.model;

import java.time.LocalDateTime;

public record AuthUserDTO(
    Long id,
    String username,
    String displayName,
    UserRole role,
    LocalDateTime lastLoginAt
) {
}
