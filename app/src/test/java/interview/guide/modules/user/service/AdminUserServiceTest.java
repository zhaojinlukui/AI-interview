package interview.guide.modules.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import interview.guide.common.exception.BusinessException;
import interview.guide.modules.interview.repository.InterviewSessionRepository;
import interview.guide.modules.resume.repository.ResumeRepository;
import interview.guide.modules.user.model.UserEntity;
import interview.guide.modules.user.model.UserRole;
import interview.guide.modules.user.repository.UserRepository;
import interview.guide.modules.voiceinterview.repository.VoiceInterviewSessionRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("管理员用户服务")
class AdminUserServiceTest {

  @Mock
  private UserRepository userRepository;

  @Mock
  private ResumeRepository resumeRepository;

  @Mock
  private InterviewSessionRepository interviewSessionRepository;

  @Mock
  private VoiceInterviewSessionRepository voiceSessionRepository;

  @Mock
  private PasswordService passwordService;

  private AdminUserService service;

  @BeforeEach
  void setUp() {
    service = new AdminUserService(
        userRepository,
        resumeRepository,
        interviewSessionRepository,
        voiceSessionRepository,
        passwordService,
        new UserMapper()
    );
  }

  @Nested
  @DisplayName("账号资料")
  class AccountProfile {

    @Test
    @DisplayName("管理员可以修改任意用户昵称")
    void updateDisplayNameTrimsAndSavesUser() {
      UserEntity user = buildUser();
      when(userRepository.findById(5L)).thenReturn(Optional.of(user));

      service.updateDisplayName(5L, "  新昵称  ");

      assertThat(user.getDisplayName()).isEqualTo("新昵称");
      verify(userRepository).save(user);
    }

    @Test
    @DisplayName("修改密码会重新哈希并递增 tokenVersion")
    void updatePasswordHashesAndRotatesTokenVersion() {
      UserEntity user = buildUser();
      user.setTokenVersion(2);
      when(userRepository.findById(5L)).thenReturn(Optional.of(user));
      when(passwordService.hash("new-password")).thenReturn("new-hash");

      service.updatePassword(5L, "new-password");

      assertThat(user.getPasswordHash()).isEqualTo("new-hash");
      assertThat(user.getTokenVersion()).isEqualTo(3);
      verify(userRepository).save(user);
    }

    @Test
    @DisplayName("用户不存在时抛出业务异常")
    void missingUserThrowsBusinessException() {
      when(userRepository.findById(404L)).thenReturn(Optional.empty());

      assertThatThrownBy(() -> service.updateDisplayName(404L, "name"))
          .isInstanceOf(BusinessException.class);
      verify(userRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }
  }

  private UserEntity buildUser() {
    return UserEntity.builder()
        .id(5L)
        .username("alice")
        .displayName("Alice")
        .passwordHash("old-hash")
        .role(UserRole.USER)
        .enabled(true)
        .tokenVersion(0)
        .build();
  }
}
