package interview.guide.modules.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

@ExtendWith(MockitoExtension.class)
class AdminBootstrapServiceTest {

  @Mock
  private UserRepository userRepository;

  @Mock
  private PasswordService passwordService;

  private AuthProperties authProperties;
  private AdminBootstrapService service;

  @BeforeEach
  void setUp() {
    authProperties = new AuthProperties();
    service = new AdminBootstrapService(
        authProperties,
        userRepository,
        passwordService,
        new ImmediateTransactionTemplate()
    );
  }

  @Nested
  @DisplayName("内置账号")
  class BuiltInAccount {

    @Test
    @DisplayName("默认管理员为 admin")
    void defaultAdminIsConfigured() {
      assertThat(authProperties.getAdmin().isInitializeOnStartup()).isFalse();
      assertThat(authProperties.getAdmin().getUsername()).isEqualTo("admin");
      assertThat(authProperties.getAdmin().getPassword()).isEqualTo("123456");
      assertThat(authProperties.getAdmin().getDisplayName()).isEqualTo("系统管理员");
    }

    @Test
    @DisplayName("默认关闭启动初始化时不写入管理员账号")
    void skipsAdminBootstrapByDefault() {
      service.initializeAdmin();

      verifyNoInteractions(userRepository, passwordService);
    }

    @Test
    @DisplayName("启动时只创建管理员账号，不创建历史数据归属用户")
    void createsOnlyAdminOnStartup() {
      authProperties.getAdmin().setInitializeOnStartup(true);
      when(passwordService.hash("123456")).thenReturn("admin-hash");
      when(userRepository.findByUsernameIgnoreCase("admin")).thenReturn(Optional.empty());
      when(userRepository.save(any(UserEntity.class))).thenAnswer(invocation -> {
        UserEntity user = invocation.getArgument(0);
        user.setId(9L);
        return user;
      });

      service.initializeAdmin();

      ArgumentCaptor<UserEntity> userCaptor = ArgumentCaptor.forClass(UserEntity.class);
      verify(userRepository, times(1)).save(userCaptor.capture());
      UserEntity saved = userCaptor.getValue();
      assertThat(saved.getUsername()).isEqualTo("admin");
      assertThat(saved.getPasswordHash()).isEqualTo("admin-hash");
      assertThat(saved.getRole()).isEqualTo(UserRole.ADMIN);
      assertThat(saved.getEnabled()).isTrue();
      verify(userRepository, never()).findByUsernameIgnoreCase("zhaojin");
    }

    @Test
    @DisplayName("已存在管理员的数据库密码不会被启动配置覆盖，角色按配置修正")
    void keepsExistingDatabasePasswordsAndFixesRoles() {
      authProperties.getAdmin().setInitializeOnStartup(true);
      UserEntity existingAdmin = UserEntity.builder()
          .id(9L)
          .username("admin")
          .displayName("系统管理员")
          .passwordHash("admin-database-password-hash")
          .role(UserRole.USER)
          .enabled(false)
          .build();
      when(userRepository.findByUsernameIgnoreCase("admin")).thenReturn(Optional.of(existingAdmin));
      when(userRepository.save(any(UserEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

      service.initializeAdmin();

      ArgumentCaptor<UserEntity> userCaptor = ArgumentCaptor.forClass(UserEntity.class);
      verify(userRepository, times(1)).save(userCaptor.capture());
      UserEntity saved = userCaptor.getValue();
      assertThat(saved.getUsername()).isEqualTo("admin");
      assertThat(saved.getPasswordHash()).isEqualTo("admin-database-password-hash");
      assertThat(saved.getRole()).isEqualTo(UserRole.ADMIN);
      assertThat(saved.getEnabled()).isTrue();
      verify(passwordService, never()).hash(anyString());
      verify(userRepository, never()).findByUsernameIgnoreCase("zhaojin");
    }
  }

  private static class ImmediateTransactionTemplate extends TransactionTemplate {

    @Override
    public <T> T execute(TransactionCallback<T> action) throws TransactionException {
      return action.doInTransaction(null);
    }
  }
}
