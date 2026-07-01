package interview.guide.modules.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import interview.guide.common.auth.AuthProperties;
import interview.guide.modules.user.model.UserEntity;
import interview.guide.modules.user.model.UserRole;
import interview.guide.modules.user.repository.UserRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

@ExtendWith(MockitoExtension.class)
class AdminBootstrapServiceTest {

  @Mock
  private UserRepository userRepository;

  @Mock
  private PasswordService passwordService;

  @Mock
  private JdbcTemplate jdbcTemplate;

  private AuthProperties authProperties;
  private AdminBootstrapService service;

  @BeforeEach
  void setUp() {
    authProperties = new AuthProperties();
    service = new AdminBootstrapService(
        authProperties,
        userRepository,
        passwordService,
        jdbcTemplate,
        new ImmediateTransactionTemplate()
    );
  }

  @Nested
  @DisplayName("内置账号")
  class BuiltInAccount {

    @Test
    @DisplayName("默认管理员为 admin，历史数据归属用户为 zhaojin")
    void defaultAccountsAreConfigured() {
      assertThat(authProperties.getAdmin().getUsername()).isEqualTo("admin");
      assertThat(authProperties.getAdmin().getPassword()).isEqualTo("123456");
      assertThat(authProperties.getAdmin().getDisplayName()).isEqualTo("系统管理员");
      assertThat(authProperties.getLegacyOwner().getUsername()).isEqualTo("zhaojin");
      assertThat(authProperties.getLegacyOwner().getPassword()).isEqualTo("123456");
      assertThat(authProperties.getLegacyOwner().getDisplayName()).isEqualTo("zhaojin");
    }

    @Test
    @DisplayName("启动时创建 admin 和 zhaojin，并把历史数据归属迁移到 zhaojin")
    void createsAdminAndOwnerThenAssignsLegacyDataToZhaojin() {
      when(passwordService.hash("123456")).thenReturn("admin-hash", "owner-hash");
      when(userRepository.findByUsernameIgnoreCase("admin")).thenReturn(Optional.empty());
      when(userRepository.findByUsernameIgnoreCase("zhaojin")).thenReturn(Optional.empty());
      when(userRepository.save(any(UserEntity.class))).thenAnswer(invocation -> {
        UserEntity user = invocation.getArgument(0);
        user.setId("admin".equals(user.getUsername()) ? 9L : 6L);
        return user;
      });
      when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);

      service.initializeAdminAndOwnership();

      ArgumentCaptor<UserEntity> userCaptor = ArgumentCaptor.forClass(UserEntity.class);
      verify(userRepository, times(2)).save(userCaptor.capture());
      assertThat(userCaptor.getAllValues())
          .anySatisfy(saved -> {
            assertThat(saved.getUsername()).isEqualTo("admin");
            assertThat(saved.getPasswordHash()).isEqualTo("admin-hash");
            assertThat(saved.getRole()).isEqualTo(UserRole.ADMIN);
            assertThat(saved.getEnabled()).isTrue();
          })
          .anySatisfy(saved -> {
            assertThat(saved.getUsername()).isEqualTo("zhaojin");
            assertThat(saved.getPasswordHash()).isEqualTo("owner-hash");
            assertThat(saved.getRole()).isEqualTo(UserRole.USER);
            assertThat(saved.getEnabled()).isTrue();
          });

      ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
      ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
      verify(jdbcTemplate, times(24)).update(sqlCaptor.capture(), argsCaptor.capture());

      assertThat(sqlCaptor.getAllValues())
          .anySatisfy(sql -> assertThat(sql)
              .contains("UPDATE resumes SET user_id = ?")
              .contains("user_id IS NULL"))
          .anySatisfy(sql -> assertThat(sql)
              .isEqualTo("UPDATE resumes SET user_id = ? WHERE user_id = ?"));
      assertThat(argsCaptor.getAllValues())
          .anySatisfy(args -> assertThat(args).containsExactly("6", "default"))
          .anySatisfy(args -> assertThat(args).containsExactly("6", "admin"))
          .anySatisfy(args -> assertThat(args).containsExactly("6", "zhaojin"))
          .anySatisfy(args -> assertThat(args).containsExactly("6", "9"));

      ArgumentCaptor<String> executeSqlCaptor = ArgumentCaptor.forClass(String.class);
      verify(jdbcTemplate, times(6)).execute(executeSqlCaptor.capture());
      assertThat(executeSqlCaptor.getAllValues())
          .contains(
              "ALTER TABLE resumes DROP CONSTRAINT IF EXISTS resumes_file_hash_key",
              "ALTER TABLE resumes DROP CONSTRAINT IF EXISTS idx_resume_hash",
              "ALTER TABLE knowledge_bases DROP CONSTRAINT IF EXISTS knowledge_bases_file_hash_key",
              "ALTER TABLE knowledge_bases DROP CONSTRAINT IF EXISTS idx_kb_hash",
              "DROP INDEX IF EXISTS idx_resume_hash",
              "DROP INDEX IF EXISTS idx_kb_hash"
          );
    }

    @Test
    @DisplayName("已存在账号的数据库密码不会被启动配置覆盖，角色按配置修正")
    void keepsExistingDatabasePasswordsAndFixesRoles() {
      UserEntity existingAdmin = UserEntity.builder()
          .id(9L)
          .username("admin")
          .displayName("系统管理员")
          .passwordHash("admin-database-password-hash")
          .role(UserRole.USER)
          .enabled(false)
          .build();
      UserEntity existingOwner = UserEntity.builder()
          .id(7L)
          .username("zhaojin")
          .displayName("zhaojin")
          .passwordHash("owner-database-password-hash")
          .role(UserRole.ADMIN)
          .enabled(false)
          .build();
      when(userRepository.findByUsernameIgnoreCase("admin")).thenReturn(Optional.of(existingAdmin));
      when(userRepository.findByUsernameIgnoreCase("zhaojin")).thenReturn(Optional.of(existingOwner));
      when(userRepository.save(any(UserEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
      when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(0);

      service.initializeAdminAndOwnership();

      ArgumentCaptor<UserEntity> userCaptor = ArgumentCaptor.forClass(UserEntity.class);
      verify(userRepository, times(2)).save(userCaptor.capture());
      assertThat(userCaptor.getAllValues())
          .anySatisfy(saved -> {
            assertThat(saved.getUsername()).isEqualTo("admin");
            assertThat(saved.getPasswordHash()).isEqualTo("admin-database-password-hash");
            assertThat(saved.getRole()).isEqualTo(UserRole.ADMIN);
            assertThat(saved.getEnabled()).isTrue();
          })
          .anySatisfy(saved -> {
            assertThat(saved.getUsername()).isEqualTo("zhaojin");
            assertThat(saved.getPasswordHash()).isEqualTo("owner-database-password-hash");
            assertThat(saved.getRole()).isEqualTo(UserRole.USER);
            assertThat(saved.getEnabled()).isTrue();
          });
      verify(passwordService, never()).hash(anyString());
    }
  }

  private static class ImmediateTransactionTemplate extends TransactionTemplate {

    @Override
    public <T> T execute(TransactionCallback<T> action) throws TransactionException {
      return action.doInTransaction(null);
    }
  }
}
