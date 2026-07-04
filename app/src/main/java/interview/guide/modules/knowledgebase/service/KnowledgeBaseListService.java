package interview.guide.modules.knowledgebase.service;

import interview.guide.common.auth.CurrentUserContext;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.infrastructure.file.FileStorageService;
import interview.guide.infrastructure.mapper.KnowledgeBaseMapper;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseEntity;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseListItemDTO;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseStatsDTO;
import interview.guide.modules.knowledgebase.model.RagChatMessageEntity.MessageType;
import interview.guide.modules.knowledgebase.model.VectorStatus;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseRepository;
import interview.guide.modules.knowledgebase.repository.RagChatMessageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * 知识库列表服务
 * 负责知识库的列表查询、搜索、统计和文件下载，
 * 支持按向量状态筛选和多种排序方式
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeBaseListService {

    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final RagChatMessageRepository ragChatMessageRepository;
    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final FileStorageService fileStorageService;

    /**
     * 获取知识库列表
     * 支持按向量状态筛选和按时间/大小/提问次数排序
     */
    public List<KnowledgeBaseListItemDTO> listKnowledgeBases(VectorStatus vectorStatus, String sortBy) {
        String userId = CurrentUserContext.getRequiredUserId();
        // 按向量状态筛选
        List<KnowledgeBaseEntity> entities = vectorStatus != null
                ? knowledgeBaseRepository.findByUserIdAndVectorStatusOrderByUploadedAtDesc(userId, vectorStatus)
                : knowledgeBaseRepository.findAllByUserIdOrderByUploadedAtDesc(userId);

        // 按指定字段排序（默认按时间降序）
        if (sortBy != null && !sortBy.isBlank() && !sortBy.equalsIgnoreCase("time")) {
            entities = sortEntities(entities, sortBy);
        }

        return knowledgeBaseMapper.toListItemDTOList(entities);
    }

    /**
     * 获取全部知识库列表（无筛选条件）
     */
    public List<KnowledgeBaseListItemDTO> listKnowledgeBases() {
        return listKnowledgeBases(null, null);
    }

    /**
     * 按关键词搜索知识库
     * 搜索文件名和内容中包含关键词的知识库
     */
    public List<KnowledgeBaseListItemDTO> search(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return listKnowledgeBases();
        }
        return knowledgeBaseMapper.toListItemDTOList(
                knowledgeBaseRepository.searchByUserIdAndKeyword(
                        CurrentUserContext.getRequiredUserId(),
                        keyword.trim()
                )
        );
    }

    /**
     * 按指定字段对知识库列表排序
     * 支持按文件大小和提问次数降序排列
     */
    private List<KnowledgeBaseEntity> sortEntities(List<KnowledgeBaseEntity> entities, String sortBy) {
        return switch (sortBy.toLowerCase()) {
            case "size" -> entities.stream()
                    .sorted((a, b) -> Long.compare(b.getFileSize(), a.getFileSize()))
                    .toList();
            case "question" -> entities.stream()
                    .sorted((a, b) -> Integer.compare(b.getQuestionCount(), a.getQuestionCount()))
                    .toList();
            default -> entities;
        };
    }

    /**
     * 获取知识库统计数据
     * 包括知识库总数、总提问次数、已完成向量化数量和正在处理数量
     */
    public KnowledgeBaseStatsDTO getStatistics() {
        String userId = CurrentUserContext.getRequiredUserId();
        return new KnowledgeBaseStatsDTO(
                knowledgeBaseRepository.countByUserId(userId),
                ragChatMessageRepository.countByTypeAndUserId(MessageType.USER, userId),
                knowledgeBaseRepository.countByUserIdAndVectorStatus(userId, VectorStatus.COMPLETED),
                knowledgeBaseRepository.countByUserIdAndVectorStatus(userId, VectorStatus.PROCESSING)
        );
    }

    /**
     * 下载知识库源文件
     * 校验权限后通过文件存储服务下载原始文件
     */
    public byte[] downloadFile(Long id) {
        KnowledgeBaseEntity entity = getEntityForDownload(id);
        if (entity.getStorageKey() == null || entity.getStorageKey().isBlank()) {
            throw new BusinessException(ErrorCode.STORAGE_DOWNLOAD_FAILED, "文件存储标识缺失");
        }
        log.info("正在下载知识库文件: id={}, filename={}", id, entity.getOriginalFilename());
        return fileStorageService.downloadFile(entity.getStorageKey());
    }

    /**
     * 获取可下载的知识库实体
     * 校验用户对该知识库的归属权限
     */
    public KnowledgeBaseEntity getEntityForDownload(Long id) {
        return knowledgeBaseRepository.findByIdAndUserId(id, CurrentUserContext.getRequiredUserId())
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.KNOWLEDGE_BASE_NOT_FOUND,
                        "知识库不存在: " + id
                ));
    }
}