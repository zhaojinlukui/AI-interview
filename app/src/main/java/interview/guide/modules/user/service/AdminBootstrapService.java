package interview.guide.modules.user.service;

import interview.guide.common.auth.AuthProperties;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.user.model.UserEntity;
import interview.guide.modules.user.model.UserRole;
import interview.guide.modules.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 应用启动时初始化管理员账号
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminBootstrapService {

    private final AuthProperties authProperties;
    private final UserRepository userRepository;
    private final PasswordService passwordService;
    private final TransactionTemplate transactionTemplate;

    /**
     * 应用启动完成后初始化管理员账号
     */
    @EventListener(ApplicationReadyEvent.class)
    public void initializeAdmin() {
        if (!authProperties.getAdmin().isInitializeOnStartup()) {
            log.info("管理员账号启动初始化已关闭");
            return;
        }
        UserEntity admin = transactionTemplate.execute(status -> createOrUpdateAdmin());
        if (admin == null) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "管理员账号初始化失败");
        }
    }

    /**
     * 创建或更新管理员账号
     */
    private UserEntity createOrUpdateAdmin() {
        String username = authProperties.getAdmin().getUsername().trim();
        String password = authProperties.getAdmin().getPassword();
        validateAccountConfig("管理员", username, password);
        UserEntity admin = userRepository.findByUsernameIgnoreCase(username)
                .orElseGet(() -> UserEntity.builder()
                        .username(username)
                        .displayName(authProperties.getAdmin().getDisplayName())
                        .passwordHash(passwordService.hash(password))
                        .role(UserRole.ADMIN)
                        .enabled(true)
                        .build());

        if (admin.getRole() != UserRole.ADMIN) {
            admin.setRole(UserRole.ADMIN);
            admin.rotateTokenVersion();
        }
        admin.setEnabled(true);
        if (admin.getDisplayName() == null || admin.getDisplayName().isBlank()) {
            admin.setDisplayName(authProperties.getAdmin().getDisplayName());
        }
        boolean shouldResetPassword = authProperties.getAdmin().isResetPasswordOnStartup()
                || admin.getPasswordHash() == null
                || admin.getPasswordHash().isBlank();
        if (shouldResetPassword) {
            admin.setPasswordHash(passwordService.hash(password));
            admin.rotateTokenVersion();
        }
        admin = userRepository.save(admin);

        if ("123456".equals(password)) {
            log.warn("当前使用默认管理员密码，请在生产环境设置 APP_AUTH_ADMIN_PASSWORD");
        }
        log.info("管理员账号已就绪: username={}, userId={}", admin.getUsername(), admin.getId());
        return admin;
    }

    /**
     * 校验启动账号的基础配置
     */
    private void validateAccountConfig(String accountName, String username, String password) {
        if (username.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, accountName + "用户名不能为空");
        }
        if (password == null || password.length() < 6) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, accountName + "初始密码长度必须至少 6 位");
        }
    }
}
