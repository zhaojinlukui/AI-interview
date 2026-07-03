package interview.guide.modules.user.service;

import interview.guide.modules.user.model.AdminUserDTO;
import interview.guide.modules.user.model.AuthUserDTO;
import interview.guide.modules.user.model.UserEntity;
import org.springframework.stereotype.Component;

/**
 * 用户模块 DTO 映射器
 */
@Component
public class UserMapper {

    /**
     * 转换为认证场景使用的用户信息
     */
    public AuthUserDTO toAuthUserDTO(UserEntity user) {
        return new AuthUserDTO(
                user.getId(),
                user.getUsername(),
                user.getDisplayName(),
                user.getRole(),
                user.getLastLoginAt()
        );
    }

    /**
     * 转换为管理端用户展示信息，并附带业务统计数据
     */
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
