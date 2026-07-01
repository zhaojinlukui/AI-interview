package interview.guide.modules.knowledgebase.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * RAG 聊天消息实体.
 */
@Entity
@Table(
    name = "rag_chat_messages",
    indexes = {
    @Index(name = "idx_rag_message_session", columnList = "session_id"),
    @Index(name = "idx_rag_message_order", columnList = "session_id, messageOrder")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RagChatMessageEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;                      // 主键 ID

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "session_id", nullable = false)
  @ToString.Exclude
  @EqualsAndHashCode.Exclude
  private RagChatSessionEntity session; // 关联会话

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private MessageType type;             // 消息类型

  @Column(columnDefinition = "TEXT", nullable = false)
  private String content;               // 消息内容

  @Column(nullable = false)
  private Integer messageOrder;         // 消息顺序

  @Column(nullable = false, updatable = false)
  private LocalDateTime createdAt;      // 创建时间

  private LocalDateTime updatedAt;      // 更新时间

  @Builder.Default
  private Boolean completed = true;     // 是否完成

  public enum MessageType {
    USER,
    ASSISTANT
  }

  @PrePersist
  protected void onCreate() {
    createdAt = LocalDateTime.now();
    updatedAt = LocalDateTime.now();
  }

  @PreUpdate
  protected void onUpdate() {
    updatedAt = LocalDateTime.now();
  }

  public String getTypeString() {
    return type.name().toLowerCase();
  }
}
