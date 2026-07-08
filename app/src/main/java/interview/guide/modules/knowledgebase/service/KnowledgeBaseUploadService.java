package interview.guide.modules.knowledgebase.service;

import interview.guide.common.auth.CurrentUserContext;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.infrastructure.file.FileHashService;
import interview.guide.infrastructure.file.FileStorageService;
import interview.guide.infrastructure.file.FileValidationService;
import interview.guide.modules.knowledgebase.listener.VectorizeStreamProducer;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseEntity;
import interview.guide.modules.knowledgebase.model.UploadKnowledgeBaseBatchItemResponse;
import interview.guide.modules.knowledgebase.model.UploadKnowledgeBaseBatchResponse;
import interview.guide.modules.knowledgebase.model.UploadKnowledgeBaseResponse;
import interview.guide.modules.knowledgebase.model.VectorStatus;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * 知识库上传服务
 * 处理知识库上传、解析的业务逻辑
 * 向量化改为异步处理，通过 RabbitMQ 实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeBaseUploadService {

    private final KnowledgeBaseParseService parseService;
    private final KnowledgeBasePersistenceService persistenceService;
    private final FileStorageService storageService;
    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final FileValidationService fileValidationService;
    private final FileHashService fileHashService;
    private final VectorizeStreamProducer vectorizeStreamProducer;

    private static final long MAX_FILE_SIZE = 100 * 1024 * 1024; // 100MB
    private static final int MAX_BATCH_FILE_COUNT = 10;
    
    /**
     * 上传知识库文件
     *
     * @param file 知识库文件
     * @param name 知识库名称（可选，如果为空则从文件名提取）
     * @return 上传结果和存储信息（包含duplicate字段，表示是否为重复上传）
     */
    public UploadKnowledgeBaseResponse uploadKnowledgeBase(MultipartFile file, String name) {
        // 1. 验证文件
        fileValidationService.validateFile(file, MAX_FILE_SIZE, "知识库");
        String fileName = file.getOriginalFilename();
        log.info("收到知识库上传请求: {}, 大小: {} bytes", fileName, file.getSize());

        // 2. 验证文件类型
        String contentType = parseService.detectContentType(file);
        validateContentType(contentType, fileName);

        // 3. 检查知识库是否已存在（去重）
        String fileHash = fileHashService.calculateHash(file);
        String userId = CurrentUserContext.getRequiredUserId();
        Optional<KnowledgeBaseEntity> existingKb =
            knowledgeBaseRepository.findByUserIdAndFileHash(userId, fileHash);
        if (existingKb.isPresent()) {
            log.info("检测到重复知识库: userId={}, hash={}", userId, fileHash);
            return persistenceService.handleDuplicateKnowledgeBase(existingKb.get());
        }

        // 4. 解析知识库文本（用于向量化）
        String content = parseService.parseContent(file);
        if (content == null || content.trim().isEmpty()) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "无法从文件中提取文本内容，请确保文件格式正确");
        }

        // 5. 保存文件到RustFS
        String fileKey = storageService.uploadKnowledgeBase(file);
        String fileUrl = storageService.getFileUrl(fileKey);
        log.info("知识库已存储到RustFS: {}", fileKey);

        // 6. 保存知识库元数据到数据库（状态为 PENDING）
        KnowledgeBaseEntity savedKb = persistenceService.saveKnowledgeBase(
            file,
            name,
            fileKey,
            fileUrl,
            fileHash
        );

        // 7. 发送向量化任务到 RabbitMQ（异步处理）
        vectorizeStreamProducer.sendVectorizeTask(savedKb.getId(), content);

        log.info("知识库上传完成，向量化任务已入队: {}, kbId={}", fileName, savedKb.getId());

        // 8. 返回结果（状态为 PENDING，前端可轮询获取最新状态）
        return new UploadKnowledgeBaseResponse(
            new UploadKnowledgeBaseResponse.KnowledgeBaseInfo(
                savedKb.getId(),
                savedKb.getName(),
                savedKb.getFileSize(),
                content.length(),
                VectorStatus.PENDING
            ),
            new UploadKnowledgeBaseResponse.StorageInfo(fileKey, fileUrl),
            false
        );
    }

    /**
     * 批量导入知识库文件，单个文件失败不影响其他文件继续导入
     */
    public UploadKnowledgeBaseBatchResponse uploadKnowledgeBases(
        List<MultipartFile> files,
        List<String> names
    ) {
        if (files == null || files.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "请至少选择一个知识库文件");
        }
        if (files.size() > MAX_BATCH_FILE_COUNT) {
            throw new BusinessException(
                ErrorCode.BAD_REQUEST,
                "单次最多导入 " + MAX_BATCH_FILE_COUNT + " 个知识库文件"
            );
        }

        List<UploadKnowledgeBaseBatchItemResponse> items = new ArrayList<>();
        int successCount = 0;
        int duplicateCount = 0;

        for (int i = 0; i < files.size(); i++) {
            MultipartFile file = files.get(i);
            String filename = resolveFilename(file);
            try {
                UploadKnowledgeBaseResponse result = uploadKnowledgeBase(
                    file,
                    resolveName(names, i)
                );
                items.add(UploadKnowledgeBaseBatchItemResponse.success(filename, result));
                successCount++;
                if (result.duplicate()) {
                    duplicateCount++;
                }
            } catch (BusinessException e) {
                log.warn("批量导入知识库文件失败: filename={}, message={}", filename, e.getMessage());
                items.add(UploadKnowledgeBaseBatchItemResponse.failure(filename, e.getMessage()));
            } catch (Exception e) {
                log.error("批量导入知识库文件异常: filename={}", filename, e);
                items.add(UploadKnowledgeBaseBatchItemResponse.failure(filename, "导入失败，请稍后重试"));
            }
        }

        return new UploadKnowledgeBaseBatchResponse(
            items,
            files.size(),
            successCount,
            files.size() - successCount,
            duplicateCount
        );
    }

    private String resolveName(List<String> names, int index) {
        if (names == null || index >= names.size()) {
            return null;
        }
        String name = names.get(index);
        return name == null || name.isBlank() ? null : name;
    }

    private String resolveFilename(MultipartFile file) {
        String filename = file.getOriginalFilename();
        return filename == null || filename.isBlank() ? file.getName() : filename;
    }

    /**
     * 验证文件类型
     */
    private void validateContentType(String contentType, String fileName) {
        fileValidationService.validateContentType(
            contentType,
            fileName,
            fileValidationService::isKnowledgeBaseMimeType,
            fileValidationService::isMarkdownExtension,
            "不支持的文件类型: " + contentType + "，支持的类型：PDF、DOCX、DOC、TXT、MD等"
        );
    }
    
    /**
     * 重新向量化知识库（手动重试）
     * 从 RustFS 重新下载文件并发送向量化任务
     */
    public void revectorize(Long kbId) {
        KnowledgeBaseEntity kb = knowledgeBaseRepository.findByIdAndUserId(
                kbId,
                CurrentUserContext.getRequiredUserId())
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "知识库不存在"));

        log.info("开始重新向量化知识库: kbId={}, name={}", kbId, kb.getName());

        // 1. 下载文件并解析内容
        String content = parseService.downloadAndParseContent(
            kb.getStorageKey(),
            kb.getOriginalFilename()
        );
        if (content == null || content.trim().isEmpty()) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "无法从文件中提取文本内容");
        }

        // 2. 更新状态为 PENDING（通过单独的 Service 保证事务生效）
        persistenceService.updateVectorStatusToPending(kbId);

        // 3. 发送向量化任务到 RabbitMQ
        vectorizeStreamProducer.sendVectorizeTask(kbId, content);

        log.info("重新向量化任务已发送: kbId={}", kbId);
    }
}
