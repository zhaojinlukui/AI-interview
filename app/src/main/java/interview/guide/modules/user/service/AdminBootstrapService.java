package interview.guide.modules.user.service;

import interview.guide.common.auth.AuthProperties;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.user.model.UserEntity;
import interview.guide.modules.user.model.UserRole;
import interview.guide.modules.user.repository.UserRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Slf4j
@Service
@RequiredArgsConstructor
public class AdminBootstrapService {

    private static final String ADMIN_LITERAL_OWNER = "admin";
    private static final String LEGACY_DEFAULT_USER_ID = "default";
    private static final List<String> USER_OWNED_TABLES = List.of(
            "resumes",
            "interview_sessions",
            "knowledge_bases",
            "rag_chat_sessions",
            "interview_schedule",
            "voice_interview_sessions"
    );

    private final AuthProperties authProperties;
    private final UserRepository userRepository;
    private final PasswordService passwordService;
    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;

    @EventListener(ApplicationReadyEvent.class)
    public void initializeAdminAndOwnership() {
        UserEntity admin = transactionTemplate.execute(status -> createOrUpdateAdmin());
        if (admin == null) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "管理员账号初始化失败");
        }
        UserEntity legacyOwner = transactionTemplate.execute(status -> createOrUpdateLegacyOwner());
        if (legacyOwner == null) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "历史数据归属账号初始化失败");
        }
        assignLegacyDataToOwner(legacyOwner, admin);
        dropLegacyGlobalHashConstraints();
    }

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

    private UserEntity createOrUpdateLegacyOwner() {
        String username = authProperties.getLegacyOwner().getUsername().trim();
        String password = authProperties.getLegacyOwner().getPassword();
        validateAccountConfig("历史数据归属用户", username, password);
        UserEntity owner = userRepository.findByUsernameIgnoreCase(username)
                .orElseGet(() -> UserEntity.builder()
                        .username(username)
                        .displayName(authProperties.getLegacyOwner().getDisplayName())
                        .passwordHash(passwordService.hash(password))
                        .role(UserRole.USER)
                        .enabled(true)
                        .build());

        if (owner.getRole() != UserRole.USER) {
            owner.setRole(UserRole.USER);
            owner.rotateTokenVersion();
        }
        owner.setEnabled(true);
        if (owner.getDisplayName() == null || owner.getDisplayName().isBlank()) {
            owner.setDisplayName(authProperties.getLegacyOwner().getDisplayName());
        }
        if (owner.getPasswordHash() == null || owner.getPasswordHash().isBlank()) {
            owner.setPasswordHash(passwordService.hash(password));
            owner.rotateTokenVersion();
        }
        owner = userRepository.save(owner);
        log.info("历史数据归属账号已就绪: username={}, userId={}", owner.getUsername(), owner.getId());
        return owner;
    }

    private void validateAccountConfig(String accountName, String username, String password) {
        if (username.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, accountName + "用户名不能为空");
        }
        if (password == null || password.length() < 6) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, accountName + "初始密码长度必须至少 6 位");
        }
    }

    private void assignLegacyDataToOwner(UserEntity owner, UserEntity admin) {
        String ownerUserId = owner.getId().toString();
        assignUnclaimedDataToOwner(ownerUserId);
        assignLiteralOwnerToOwner(ownerUserId, ADMIN_LITERAL_OWNER);
        assignLiteralOwnerToOwner(ownerUserId, owner.getUsername());
        assignUserDataToOwner(ownerUserId, admin);
    }

    private void assignUnclaimedDataToOwner(String ownerUserId) {
        for (String table : USER_OWNED_TABLES) {
            updateIgnoringFailures(
                    "UPDATE " + table + " SET user_id = ? "
                            + "WHERE user_id IS NULL OR user_id = '' OR user_id = ?",
                    ownerUserId,
                    LEGACY_DEFAULT_USER_ID
            );
        }
    }

    private void assignLiteralOwnerToOwner(String ownerUserId, String legacyOwner) {
        for (String table : USER_OWNED_TABLES) {
            updateIgnoringFailures(
                    "UPDATE " + table + " SET user_id = ? WHERE user_id = ?",
                    ownerUserId,
                    legacyOwner
            );
        }
    }

    private void assignUserDataToOwner(String ownerUserId, UserEntity sourceUser) {
        String sourceUserId = sourceUser.getId().toString();
        if (ownerUserId.equals(sourceUserId)) {
            return;
        }
        for (String table : USER_OWNED_TABLES) {
            updateIgnoringFailures(
                    "UPDATE " + table + " SET user_id = ? WHERE user_id = ?",
                    ownerUserId,
                    sourceUserId
            );
        }
    }

    private void dropLegacyGlobalHashConstraints() {
        executeIgnoringFailures("ALTER TABLE resumes DROP CONSTRAINT IF EXISTS resumes_file_hash_key");
        executeIgnoringFailures("ALTER TABLE resumes DROP CONSTRAINT IF EXISTS idx_resume_hash");
        executeIgnoringFailures(
                "ALTER TABLE knowledge_bases DROP CONSTRAINT IF EXISTS knowledge_bases_file_hash_key");
        executeIgnoringFailures("ALTER TABLE knowledge_bases DROP CONSTRAINT IF EXISTS idx_kb_hash");
        executeIgnoringFailures("DROP INDEX IF EXISTS idx_resume_hash");
        executeIgnoringFailures("DROP INDEX IF EXISTS idx_kb_hash");
    }

    private void updateIgnoringFailures(String sql, Object... args) {
        try {
            int rows = jdbcTemplate.update(sql, args);
            if (rows > 0) {
                log.info("历史数据归属已修复: rows={}, sql={}", rows, sql);
            }
        } catch (Exception e) {
            log.debug("跳过历史数据归属修复: sql={}, error={}", sql, e.getMessage());
        }
    }

    private void executeIgnoringFailures(String sql) {
        try {
            jdbcTemplate.execute(sql);
        } catch (Exception e) {
            log.debug("跳过兼容性 schema 调整: sql={}, error={}", sql, e.getMessage());
        }
    }
}
