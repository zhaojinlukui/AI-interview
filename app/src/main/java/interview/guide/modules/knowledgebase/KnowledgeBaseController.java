package interview.guide.modules.knowledgebase;

import interview.guide.common.annotation.RateLimit;
import interview.guide.common.result.Result;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseListItemDTO;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseStatsDTO;
import interview.guide.modules.knowledgebase.model.UploadKnowledgeBaseBatchResponse;
import interview.guide.modules.knowledgebase.model.VectorStatus;
import interview.guide.modules.knowledgebase.service.KnowledgeBaseDeleteService;
import interview.guide.modules.knowledgebase.service.KnowledgeBaseListService;
import interview.guide.modules.knowledgebase.service.KnowledgeBaseUploadService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 知识库管理控制器。
 */
@RestController
@RequiredArgsConstructor
@Tag(name = "知识库", description = "知识库上传、下载、检索与向量化")
public class KnowledgeBaseController {

    private final KnowledgeBaseUploadService uploadService;
    private final KnowledgeBaseListService listService;
    private final KnowledgeBaseDeleteService deleteService;

    /**
     * 查询知识库列表。
     *
     * @param sortBy 排序字段，可选
     * @param vectorStatus 向量化状态，可选
     * @return 知识库列表
     */
    @GetMapping("/api/knowledgebase/list")
    public Result<List<KnowledgeBaseListItemDTO>> getAllKnowledgeBases(
            @RequestParam(value = "sortBy", required = false) String sortBy,
            @RequestParam(value = "vectorStatus", required = false) String vectorStatus
    ) {
        VectorStatus status = null;
        if (vectorStatus != null && !vectorStatus.isBlank()) {
            try {
                status = VectorStatus.valueOf(vectorStatus.toUpperCase());
            } catch (IllegalArgumentException e) {
                return Result.error("无效的向量化状态: " + vectorStatus);
            }
        }

        return Result.success(listService.listKnowledgeBases(status, sortBy));
    }

    /**
     * 删除知识库。
     *
     * @param id 知识库 ID
     * @return 删除结果
     */
    @DeleteMapping("/api/knowledgebase/{id}")
    public Result<Void> deleteKnowledgeBase(@PathVariable Long id) {
        deleteService.deleteKnowledgeBase(id);
        return Result.success(null);
    }

    /**
     * 批量上传知识库文件。
     *
     * @param files 知识库文件列表
     * @param names 知识库名称列表，可选
     * @return 批量上传结果
     */
    @PostMapping(
            value = "/api/knowledgebase/upload/batch",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 3)
    @RateLimit(dimension = RateLimit.Dimension.IP, count = 3)
    public Result<UploadKnowledgeBaseBatchResponse> uploadKnowledgeBases(
            @RequestParam("files") List<MultipartFile> files,
            @RequestParam(value = "names", required = false) List<String> names
    ) {
        return Result.success(uploadService.uploadKnowledgeBases(files, names));
    }

    /**
     * 下载知识库原始文件。
     *
     * @param id 知识库 ID
     * @return 文件响应
     */
    @GetMapping("/api/knowledgebase/{id}/download")
    public ResponseEntity<byte[]> downloadKnowledgeBase(@PathVariable Long id) {
        var entity = listService.getEntityForDownload(id);
        byte[] fileContent = listService.downloadFile(id);

        String filename = entity.getOriginalFilename();
        String encodedFilename = URLEncoder.encode(filename, StandardCharsets.UTF_8)
                .replaceAll("\\+", "%20");

        return ResponseEntity.ok()
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + encodedFilename + "\"; filename*=UTF-8''"
                                + encodedFilename)
                .header(
                        HttpHeaders.CONTENT_TYPE,
                        entity.getContentType() != null
                                ? entity.getContentType()
                                : MediaType.APPLICATION_OCTET_STREAM_VALUE)
                .body(fileContent);
    }

    /**
     * 按关键字搜索知识库。
     *
     * @param keyword 搜索关键字
     * @return 知识库列表
     */
    @GetMapping("/api/knowledgebase/search")
    public Result<List<KnowledgeBaseListItemDTO>> search(@RequestParam("keyword") String keyword) {
        return Result.success(listService.search(keyword));
    }

    /**
     * 获取知识库统计信息。
     *
     * @return 知识库统计信息
     */
    @GetMapping("/api/knowledgebase/stats")
    public Result<KnowledgeBaseStatsDTO> getStatistics() {
        return Result.success(listService.getStatistics());
    }

    /**
     * 重新向量化指定知识库。
     *
     * @param id 知识库 ID
     * @return 任务提交结果
     */
    @PostMapping("/api/knowledgebase/{id}/revectorize")
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 2)
    @RateLimit(dimension = RateLimit.Dimension.IP, count = 2)
    public Result<Void> revectorize(@PathVariable Long id) {
        uploadService.revectorize(id);
        return Result.success(null);
    }
}
