package interview.guide.modules.user.service;

import interview.guide.modules.user.model.AdminUserDTO;
import interview.guide.modules.user.model.AuthUserDTO;
import interview.guide.modules.user.model.UserEntity;
import org.springframework.stereotype.Component;

@Component
public class UserMapper {

    public AuthUserDTO toAuthUserDTO(UserEntity user) {
        return new AuthUserDTO(
                user.getId(),
                user.getUsername(),
                user.getDisplayName(),
                user.getRole(),
                user.getLastLoginAt()
        );
    }

    public AdminUserDTO toAdminUserDTO(UserEntity user) {
        return toAdminUserDTO(user, 0, 0, 0, user.getLastLoginAt());
    }

    public AdminUserDTO toAdminUserDTO(
            UserEntity user,
            long resumeCount,
            long textInterviewCount,
            long voiceInterviewCount,
            java.time.LocalDateTime recentActivityAt
    ) {
        return new AdminUserDTO(
                user.getId(),
                user.getUsername(),
                user.getDisplayName(),
                user.getRole(),
                user.getEnabled(),
                user.getCreatedAt(),
                user.getUpdatedAt(),
                user.getLastLoginAt(),
                resumeCount,
                textInterviewCount,
                voiceInterviewCount,
                recentActivityAt
        );
    }
}
