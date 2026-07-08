package interview.guide.modules.knowledgebase.service;

import interview.guide.common.auth.CurrentUserContext;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseEntity;
import interview.guide.modules.knowledgebase.model.UploadKnowledgeBaseResponse;
import interview.guide.modules.knowledgebase.model.VectorStatus;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;

/**
 * 知识库持久化服务
 * 处理所有需要事务的数据库操作
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeBasePersistenceService {

    private final KnowledgeBaseRepository knowledgeBaseRepository;

    /**
     * 处理重复知识库
     */
    @Transactional(rollbackFor = Exception.class)
    public UploadKnowledgeBaseResponse handleDuplicateKnowledgeBase(KnowledgeBaseEntity kb) {
        log.info("检测到重复知识库，返回已有记录: kbId={}", kb.getId());
        kb.updateAccessedAt();
        knowledgeBaseRepository.save(kb);
        
        // 重复知识库的向量数据应该已经存在，不需要重新向量化
        return new UploadKnowledgeBaseResponse(
            new UploadKnowledgeBaseResponse.KnowledgeBaseInfo(
                kb.getId(),
                kb.getName(),
                kb.getFileSize(),
                0,
                kb.getVectorStatus()
            ),
            new UploadKnowledgeBaseResponse.StorageInfo(
                kb.getStorageKey() != null ? kb.getStorageKey() : "",
                kb.getStorageUrl() != null ? kb.getStorageUrl() : ""
            ),
            true
        );
    }

    /**
     * 保存新知识库元数据到数据库
     */
    @Transactional(rollbackFor = Exception.class)
    public KnowledgeBaseEntity saveKnowledgeBase(
        MultipartFile file,
        String name,
        String storageKey,
        String storageUrl,
        String fileHash
    ) {
        try {
            KnowledgeBaseEntity kb = new KnowledgeBaseEntity();
            kb.setFileHash(fileHash);
            kb.setUserId(CurrentUserContext.getRequiredUserId());
            kb.setName(
                name != null && !name.trim().isEmpty()
                    ? name
                    : extractNameFromFilename(file.getOriginalFilename())
            );
            kb.setOriginalFilename(file.getOriginalFilename());
            kb.setFileSize(file.getSize());
            kb.setContentType(file.getContentType());
            kb.setStorageKey(storageKey);
            kb.setStorageUrl(storageUrl);

            KnowledgeBaseEntity saved = knowledgeBaseRepository.save(kb);
            log.info("知识库已保存: id={}, userId={}, name={}, hash={}",
                saved.getId(), saved.getUserId(), saved.getName(), fileHash);
            return saved;
        } catch (Exception e) {
            log.error("保存知识库失败: {}", e.getMessage(), e);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "保存知识库失败");
        }
    }

    /**
     * 更新知识库向量化状态为 PENDING
     */
    @Transactional(rollbackFor = Exception.class)
    public void updateVectorStatusToPending(Long kbId) {
        KnowledgeBaseEntity kb = knowledgeBaseRepository.findByIdAndUserId(
                kbId,
                CurrentUserContext.getRequiredUserId())
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "知识库不存在"));
        
        kb.setVectorStatus(VectorStatus.PENDING);
        kb.setVectorError(null);
        knowledgeBaseRepository.save(kb);
        
        log.info("知识库向量化状态已更新为 PENDING: kbId={}", kbId);
    }

    /**
     * 从文件名提取知识库名称（去除扩展名）
     */
    private String extractNameFromFilename(String filename) {
        if (filename == null || filename.isEmpty()) {
            return "未命名知识库";
        }
        int lastDot = filename.lastIndexOf('.');
        if (lastDot > 0) {
            return filename.substring(0, lastDot);
        }
        return filename;
    }
}
