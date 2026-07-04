package interview.guide.modules.knowledgebase.repository;

import interview.guide.modules.knowledgebase.model.RagChatSessionEntity;
import interview.guide.modules.knowledgebase.model.RagChatSessionEntity.SessionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/** 
 * RAG聊天会话Repository
 */
@Repository
public interface RagChatSessionRepository extends JpaRepository<RagChatSessionEntity, Long> {

    /**
     * 获取所有会话（按置顶状态和更新时间排序：置顶的在前，然后按更新时间倒序）
     */
    @Query("""
        SELECT s FROM RagChatSessionEntity s
        WHERE s.userId = :userId
        ORDER BY s.isPinned DESC, s.updatedAt DESC
        """)
    List<RagChatSessionEntity> findAllByUserIdOrderByPinnedAndUpdatedAtDesc(
        @Param("userId") String userId
    );

    /**
     * 根据知识库ID查找相关会话
     */
    @Query("""
        SELECT DISTINCT s FROM RagChatSessionEntity s JOIN s.knowledgeBases kb
        WHERE s.userId = :userId AND kb.id IN :kbIds
        ORDER BY s.updatedAt DESC
        """)
    List<RagChatSessionEntity> findByUserIdAndKnowledgeBaseIds(
        @Param("userId") String userId,
        @Param("kbIds") List<Long> knowledgeBaseIds
    );

    @Query("""
        SELECT s FROM RagChatSessionEntity s LEFT JOIN FETCH s.knowledgeBases
        WHERE s.id = :id AND s.userId = :userId
        """)
    Optional<RagChatSessionEntity> findByIdAndUserIdWithKnowledgeBases(
        @Param("id") Long id,
        @Param("userId") String userId
    );

    boolean existsByIdAndUserId(Long id, String userId);

    void deleteByUserId(String userId);
}
