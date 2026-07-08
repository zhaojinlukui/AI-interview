package interview.guide.modules.knowledgebase.model;

import jakarta.persistence.CascadeType;
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
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * RAG 聊天会话实体.
 */
@Entity
@Table(
        name = "rag_chat_sessions",
        indexes = {
                @Index(name = "idx_rag_session_user_updated", columnList = "userId,updatedAt")
        })
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RagChatSessionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;                                 // 主键 ID

    @Column(nullable = false)
    private String title;                            // 会话标题

    @Column(length = 64)
    private String userId;                           // 所属用户 ID

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    @Builder.Default
    private SessionStatus status = SessionStatus.ACTIVE; // 会话状态

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
            name = "rag_session_knowledge_bases",
            joinColumns = @JoinColumn(name = "session_id"),
            inverseJoinColumns = @JoinColumn(name = "knowledge_base_id")
    )
    @Builder.Default
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private Set<KnowledgeBaseEntity> knowledgeBases = new HashSet<>(); // 关联知识库集合

    @OneToMany(mappedBy = "session", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("messageOrder ASC")
    @Builder.Default
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private List<RagChatMessageEntity> messages = new ArrayList<>(); // 会话消息列表

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;                 // 创建时间

    private LocalDateTime updatedAt;                 // 更新时间

    @Builder.Default
    private Integer messageCount = 0;                // 消息数量

    @Column(columnDefinition = "boolean default false")
    @Builder.Default
    private Boolean isPinned = false;                // 是否置顶

    public enum SessionStatus {
        ACTIVE,
        ARCHIVED
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

    @PostLoad
    protected void onLoad() {
        if (isPinned == null) {
            isPinned = false;
        }
    }

    public List<Long> getKnowledgeBaseIds() {
        return knowledgeBases.stream()
                .map(KnowledgeBaseEntity::getId)
                .toList();
    }
}
