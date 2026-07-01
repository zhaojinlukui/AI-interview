package interview.guide.modules.user.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.interview.repository.InterviewSessionRepository;
import interview.guide.modules.resume.repository.ResumeRepository;
import interview.guide.modules.user.model.AdminUserDTO;
import interview.guide.modules.user.model.UserEntity;
import interview.guide.modules.user.repository.UserRepository;
import interview.guide.modules.voiceinterview.repository.VoiceInterviewSessionRepository;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AdminUserService {

  private final UserRepository userRepository;
  private final ResumeRepository resumeRepository;
  private final InterviewSessionRepository interviewSessionRepository;
  private final VoiceInterviewSessionRepository voiceSessionRepository;
  private final PasswordService passwordService;
  private final UserMapper userMapper;

  public List<AdminUserDTO> listUsers() {
    return userRepository.findAllByOrderByCreatedAtDesc().stream()
        .map(this::toAdminUserDTO)
        .toList();
  }

  @Transactional
  public void updateDisplayName(Long userId, String displayName) {
    UserEntity user = findUser(userId);
    user.setDisplayName(displayName.trim());
    userRepository.save(user);
  }

  @Transactional
  public void updatePassword(Long userId, String newPassword) {
    UserEntity user = findUser(userId);
    user.setPasswordHash(passwordService.hash(newPassword));
    user.rotateTokenVersion();
    userRepository.save(user);
  }

  private UserEntity findUser(Long userId) {
    return userRepository.findById(userId)
        .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND, "User not found"));
  }

  private AdminUserDTO toAdminUserDTO(UserEntity user) {
    String userId = user.getId().toString();
    long resumeCount = resumeRepository.countByUserId(userId);
    long textInterviewCount = interviewSessionRepository.countByUserId(userId);
    long voiceInterviewCount = voiceSessionRepository.countByUserId(userId);
    LocalDateTime recentActivityAt = maxTime(
        user.getLastLoginAt(),
        resumeRepository.findFirstByUserIdOrderByUploadedAtDesc(userId)
            .map(resume -> resume.getUploadedAt())
            .orElse(null),
        interviewSessionRepository.findFirstByUserIdOrderByCreatedAtDesc(userId)
            .map(session -> session.getCreatedAt())
            .orElse(null),
        voiceSessionRepository.findFirstByUserIdOrderByUpdatedAtDesc(userId)
            .map(session -> session.getUpdatedAt())
            .orElse(null)
    );

    return userMapper.toAdminUserDTO(
        user,
        resumeCount,
        textInterviewCount,
        voiceInterviewCount,
        recentActivityAt
    );
  }

  private LocalDateTime maxTime(LocalDateTime... values) {
    LocalDateTime latest = null;
    for (LocalDateTime value : values) {
      if (value != null && (latest == null || value.isAfter(latest))) {
        latest = value;
      }
    }
    return latest;
  }
}
