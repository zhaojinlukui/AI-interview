package interview.guide.modules.voiceinterview.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "voice_interview_messages")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VoiceInterviewMessageEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;                                 // 主键 ID

  @Column(name = "session_id")
  private Long sessionId;                          // 语音面试会话 ID

  @Column(name = "message_type", nullable = false)
  private String messageType;                      // 消息类型（USER_SPEECH/AI_SPEECH/SYSTEM）

  @Column(name = "phase")
  @Enumerated(EnumType.STRING)
  private VoiceInterviewSessionEntity.InterviewPhase phase; // 面试阶段

  @Column(name = "user_recognized_text", columnDefinition = "TEXT")
  private String userRecognizedText;               // 用户语音识别文本

  @Column(name = "ai_generated_text", columnDefinition = "TEXT")
  private String aiGeneratedText;                  // AI 生成文本

  @Column(name = "timestamp")
  private LocalDateTime timestamp;                 // 消息时间

  @Column(name = "sequence_num")
  private Integer sequenceNum;                     // 消息序号

  @Column(name = "created_at")
  private LocalDateTime createdAt;                 // 创建时间

  @PrePersist
  protected void onCreate() {
    this.createdAt = LocalDateTime.now();
    this.timestamp = LocalDateTime.now();
  }

  public static String trimToNull(String text) {
    if (text == null || text.isBlank()) {
      return null;
    }
    return text.trim();
  }
}
