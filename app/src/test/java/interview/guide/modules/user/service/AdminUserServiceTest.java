package interview.guide.modules.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import interview.guide.common.exception.BusinessException;
import interview.guide.modules.aisettings.repository.UserAiSettingsRepository;
import interview.guide.modules.interview.repository.InterviewSessionRepository;
import interview.guide.modules.interviewschedule.repository.InterviewScheduleRepository;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseRepository;
import interview.guide.modules.knowledgebase.repository.RagChatSessionRepository;
import interview.guide.modules.resume.repository.ResumeAnalysisRepository;
import interview.guide.modules.resume.repository.ResumeRepository;
import interview.guide.modules.user.model.UserEntity;
import interview.guide.modules.user.model.UserRole;
import interview.guide.modules.user.repository.UserRepository;
import interview.guide.modules.voiceinterview.model.VoiceInterviewSessionEntity;
import interview.guide.modules.voiceinterview.repository.VoiceInterviewEvaluationRepository;
import interview.guide.modules.voiceinterview.repository.VoiceInterviewMessageRepository;
import interview.guide.modules.voiceinterview.repository.VoiceInterviewSessionRepository;
import java.util.List;
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
  private ResumeAnalysisRepository resumeAnalysisRepository;

  @Mock
  private InterviewSessionRepository interviewSessionRepository;

  @Mock
  private VoiceInterviewSessionRepository voiceSessionRepository;

  @Mock
  private VoiceInterviewMessageRepository voiceMessageRepository;

  @Mock
  private VoiceInterviewEvaluationRepository voiceEvaluationRepository;

  @Mock
  private KnowledgeBaseRepository knowledgeBaseRepository;

  @Mock
  private RagChatSessionRepository ragChatSessionRepository;

  @Mock
  private InterviewScheduleRepository scheduleRepository;

  @Mock
  private UserAiSettingsRepository userAiSettingsRepository;

  @Mock
  private PasswordService passwordService;

  private AdminUserService service;

  @BeforeEach
  void setUp() {
    service = new AdminUserService(
        userRepository,
        resumeRepository,
        resumeAnalysisRepository,
        interviewSessionRepository,
        voiceSessionRepository,
        voiceMessageRepository,
        voiceEvaluationRepository,
        knowledgeBaseRepository,
        ragChatSessionRepository,
        scheduleRepository,
        userAiSettingsRepository,
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

  @Nested
  @DisplayName("账号状态")
  class AccountStatus {

    @Test
    @DisplayName("禁用账号会修改状态并递增 tokenVersion")
    void updateEnabledDisablesUserAndRotatesTokenVersion() {
      UserEntity user = buildUser();
      when(userRepository.findById(5L)).thenReturn(Optional.of(user));

      service.updateEnabled(5L, false);

      assertThat(user.getEnabled()).isFalse();
      assertThat(user.getTokenVersion()).isEqualTo(1);
      verify(userRepository).save(user);
    }

    @Test
    @DisplayName("状态未变化时不保存")
    void updateEnabledSkipsWhenStateIsUnchanged() {
      UserEntity user = buildUser();
      when(userRepository.findById(5L)).thenReturn(Optional.of(user));

      service.updateEnabled(5L, true);

      assertThat(user.getTokenVersion()).isZero();
      verify(userRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("不允许修改管理员账号启用状态")
    void updateEnabledRejectsAdminAccount() {
      UserEntity admin = buildAdmin();
      when(userRepository.findById(1L)).thenReturn(Optional.of(admin));

      assertThatThrownBy(() -> service.updateEnabled(1L, false))
          .isInstanceOf(BusinessException.class);
      verify(userRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }
  }

  @Nested
  @DisplayName("账号删除")
  class AccountDeletion {

    @Test
    @DisplayName("管理员删除普通用户账号时清理用户所属业务记录")
    void deleteUserDeletesRegularUserAndOwnedRecords() {
      UserEntity user = buildUser();
      VoiceInterviewSessionEntity voiceSession = VoiceInterviewSessionEntity.builder()
          .id(9L)
          .userId("5")
          .roleType("java")
          .build();
      when(userRepository.findById(5L)).thenReturn(Optional.of(user));
      when(voiceSessionRepository.findByUserIdOrderByUpdatedAtDesc("5"))
          .thenReturn(List.of(voiceSession));

      service.deleteUser(5L);

      verify(voiceMessageRepository).deleteBySessionIdIn(List.of(9L));
      verify(voiceEvaluationRepository).deleteBySessionIdIn(List.of(9L));
      verify(voiceSessionRepository).deleteByUserId("5");
      verify(ragChatSessionRepository).deleteByUserId("5");
      verify(knowledgeBaseRepository).deleteByUserId("5");
      verify(interviewSessionRepository).deleteByUserId("5");
      verify(resumeAnalysisRepository).deleteByResumeUserId("5");
      verify(resumeRepository).deleteByUserId("5");
      verify(scheduleRepository).deleteByUserId("5");
      verify(userAiSettingsRepository).deleteByUserId("5");
      verify(userRepository).delete(user);
    }

    @Test
    @DisplayName("不允许删除管理员账号")
    void deleteUserRejectsAdminAccount() {
      UserEntity admin = buildAdmin();
      when(userRepository.findById(1L)).thenReturn(Optional.of(admin));

      assertThatThrownBy(() -> service.deleteUser(1L))
          .isInstanceOf(BusinessException.class);
      verify(userRepository, never()).delete(org.mockito.ArgumentMatchers.any());
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

  private UserEntity buildAdmin() {
    return UserEntity.builder()
        .id(1L)
        .username("admin")
        .displayName("管理员")
        .passwordHash("admin-hash")
        .role(UserRole.ADMIN)
        .enabled(true)
        .tokenVersion(0)
        .build();
  }
}