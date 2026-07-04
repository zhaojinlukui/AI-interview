package interview.guide.modules.knowledgebase.repository;

import interview.guide.modules.knowledgebase.model.KnowledgeBaseEntity;
import interview.guide.modules.knowledgebase.model.VectorStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 知识库Repository
 */
@Repository
public interface KnowledgeBaseRepository extends JpaRepository<KnowledgeBaseEntity, Long> {

    /**
     * 根据文件哈希查找知识库（用于去重）
     */
    Optional<KnowledgeBaseEntity> findByUserIdAndFileHash(String userId, String fileHash);

    /**
     * 按上传时间倒序查找所有知识库
     */
    List<KnowledgeBaseEntity> findAllByUserIdOrderByUploadedAtDesc(String userId);

    /**
     * 按名称或文件名模糊搜索（不区分大小写）
     */
    @Query("""
        SELECT k FROM KnowledgeBaseEntity k
        WHERE k.userId = :userId
          AND (LOWER(k.name) LIKE LOWER(CONCAT('%', :keyword, '%'))
            OR LOWER(k.originalFilename) LIKE LOWER(CONCAT('%', :keyword, '%')))
        ORDER BY k.uploadedAt DESC
        """)
    List<KnowledgeBaseEntity> searchByUserIdAndKeyword(
        @Param("userId") String userId,
        @Param("keyword") String keyword
    );

    // ==================== 批量更新 ====================

    /**
     * 批量增加知识库提问计数
     */
    @Modifying
    @Query("UPDATE KnowledgeBaseEntity k SET k.questionCount = k.questionCount + 1 WHERE k.id IN :ids")
    int incrementQuestionCountBatch(@Param("ids") List<Long> ids);

    // ==================== 统计查询 ====================

    /**
     * 按向量化状态统计数量
     */
    long countByUserIdAndVectorStatus(String userId, VectorStatus vectorStatus);

    long countByUserId(String userId);

    /**
     * 按向量化状态查找知识库（按上传时间倒序）
     */
    List<KnowledgeBaseEntity> findByUserIdAndVectorStatusOrderByUploadedAtDesc(
        String userId,
        VectorStatus vectorStatus
    );

    Optional<KnowledgeBaseEntity> findByIdAndUserId(Long id, String userId);

    void deleteByUserId(String userId);
}
