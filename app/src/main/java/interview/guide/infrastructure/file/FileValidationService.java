package interview.guide.infrastructure.file;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.function.Predicate;

/**
 * 文件验证服务
 * 提供通用的文件验证功能
 */
@Slf4j
@Service
public class FileValidationService {
    
    /**
     * 验证文件基本属性（是否为空、文件大小）
     */
    public void validateFile(MultipartFile file, long maxSizeBytes, String fileTypeName) {
        if (file.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, String.format("请选择要上传的%s文件", fileTypeName));
        }
        if (file.getSize() > maxSizeBytes) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    String.format("%s文件大小超过限制，最大支持 %s", fileTypeName, formatFileSize(maxSizeBytes)));
        }
    }
    
    /**
     * 验证文件类型（基于MIME类型）
     */
    public void validateContentTypeByList(String contentType, List<String> allowedTypes, String errorMessage) {
        if (!isAllowedType(contentType, allowedTypes)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, 
                errorMessage != null ? errorMessage : "不支持的文件类型: " + contentType);
        }
    }
    
    /**
     * 验证文件类型（基于MIME类型和文件扩展名）
     */
    public void validateContentType(String contentType, String fileName,
                                   Predicate<String> mimeTypeChecker,
                                   Predicate<String> extensionChecker,
                                   String errorMessage) {
        // 先检查MIME类型
        if (mimeTypeChecker.test(contentType)) {
            return;
        }
        
        // 如果MIME类型不支持，再检查文件扩展名
        if (fileName != null && extensionChecker.test(fileName)) {
            return;
        }
        
        throw new BusinessException(ErrorCode.BAD_REQUEST, 
            errorMessage != null ? errorMessage : "不支持的文件类型: " + contentType);
    }
    
    /**
     * 检查文件类型是否在允许列表中
     */
    private boolean isAllowedType(String contentType, List<String> allowedTypes) {
        if (contentType == null || allowedTypes == null || allowedTypes.isEmpty()) {
            return false;
        }
        
        String lowerContentType = contentType.toLowerCase();
        return allowedTypes.stream()
            .anyMatch(allowed -> {
                String lowerAllowed = allowed.toLowerCase();
                return lowerContentType.contains(lowerAllowed) || lowerAllowed.contains(lowerContentType);
            });
    }

    private String formatFileSize(long bytes) {
        if (bytes % (1024 * 1024) == 0) {
            return bytes / (1024 * 1024) + "MB";
        }
        if (bytes % 1024 == 0) {
            return bytes / 1024 + "KB";
        }
        return bytes + "B";
    }
    
    /**
     * 检查文件扩展名是否为Markdown格式
     */
    public boolean isMarkdownExtension(String fileName) {
        if (fileName == null) {
            return false;
        }
        
        String lowerFileName = fileName.toLowerCase();
        return lowerFileName.endsWith(".md") ||
               lowerFileName.endsWith(".markdown") ||
               lowerFileName.endsWith(".mdown");
    }
    
    /**
     * 检查MIME类型是否为知识库支持的格式
     */
    public boolean isKnowledgeBaseMimeType(String contentType) {
        if (contentType == null) {
            return false;
        }
        
        String lowerContentType = contentType.toLowerCase();
        return lowerContentType.contains("pdf") ||
               lowerContentType.contains("msword") ||
               lowerContentType.contains("wordprocessingml") ||
               lowerContentType.contains("text/plain") ||
               lowerContentType.contains("text/markdown") ||
               lowerContentType.contains("text/x-markdown") ||
               lowerContentType.contains("text/x-web-markdown") ||
               lowerContentType.contains("application/rtf");
    }
}
