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

/**
 * 管理端用户维护服务
 */
@Service
@RequiredArgsConstructor
public class AdminUserService {

    private final UserRepository userRepository;
    private final ResumeRepository resumeRepository;
    private final InterviewSessionRepository interviewSessionRepository;
    private final VoiceInterviewSessionRepository voiceSessionRepository;
    private final PasswordService passwordService;
    private final UserMapper userMapper;

    /**
     * 按创建时间倒序查询用户列表
     */
    public List<AdminUserDTO> listUsers() {
        return userRepository.findAllByOrderByCreatedAtDesc().stream()
                .map(this::toAdminUserDTO)
                .toList();
    }

    /**
     * 管理员修改指定用户昵称
     */
    @Transactional
    public void updateDisplayName(Long userId, String displayName) {
        UserEntity user = findUser(userId);
        user.setDisplayName(displayName.trim());
        userRepository.save(user);
    }

    /**
     * 管理员重置指定用户密码
     */
    @Transactional
    public void updatePassword(Long userId, String newPassword) {
        UserEntity user = findUser(userId);
        user.setPasswordHash(passwordService.hash(newPassword));
        user.rotateTokenVersion();
        userRepository.save(user);
    }

    /**
     * 查询用户，不存在时抛出业务异常
     */
    private UserEntity findUser(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND, "User not found"));
    }

    /**
     * 汇总用户在各业务模块中的使用统计
     */
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

    /**
     * 从多个时间中取最近一次活动时间
     */
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
