package interview.guide.modules.voiceinterview.repository;

import interview.guide.common.model.AsyncTaskStatus;
import interview.guide.modules.voiceinterview.model.VoiceInterviewSessionEntity;
import interview.guide.modules.voiceinterview.model.VoiceInterviewSessionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 语音面试会话 Repository。
 */
@Repository
public interface VoiceInterviewSessionRepository extends JpaRepository<VoiceInterviewSessionEntity, Long> {

  /**
   * 根据用户 ID 查询会话，按开始时间倒序排列。
   */
  List<VoiceInterviewSessionEntity> findByUserIdOrderByStartTimeDesc(String userId);

  /**
   * 查询指定评估状态且结束时间早于给定时间的会话。
   *
   * <p>这里查询的是 AsyncTaskStatus 字段，不是 InterviewPhase。</p>
   */
  Optional<VoiceInterviewSessionEntity> findByStatusAndEndTimeBefore(
      AsyncTaskStatus status,
      LocalDateTime time
  );

  /**
   * 查询用户全部会话，按更新时间倒序排列。
   */
  List<VoiceInterviewSessionEntity> findByUserIdOrderByUpdatedAtDesc(String userId);

  long countByUserId(String userId);

  Optional<VoiceInterviewSessionEntity> findFirstByUserIdOrderByUpdatedAtDesc(String userId);

  List<VoiceInterviewSessionEntity> findByUserIdAndCreatedAtGreaterThanEqualOrderByCreatedAtAsc(
      String userId,
      LocalDateTime start
  );

  Optional<VoiceInterviewSessionEntity> findByIdAndUserId(Long id, String userId);

  /**
   * 查询用户指定状态的会话，按更新时间倒序排列。
   */
  List<VoiceInterviewSessionEntity> findByUserIdAndStatusOrderByUpdatedAtDesc(
      String userId,
      VoiceInterviewSessionStatus status
  );

  List<VoiceInterviewSessionEntity> findByStatusAndStartTimeBefore(
      VoiceInterviewSessionStatus status,
      LocalDateTime time
  );

  List<VoiceInterviewSessionEntity> findByEvaluateStatusAndUpdatedAtBefore(
      AsyncTaskStatus evaluateStatus,
      LocalDateTime time
  );

  void deleteByUserId(String userId);
}
