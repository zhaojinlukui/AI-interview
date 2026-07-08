package interview.guide.infrastructure.file;

import interview.guide.common.config.StorageConfigProperties;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.sourceforge.pinyin4j.PinyinHelper;
import net.sourceforge.pinyin4j.format.HanyuPinyinCaseType;
import net.sourceforge.pinyin4j.format.HanyuPinyinOutputFormat;
import net.sourceforge.pinyin4j.format.HanyuPinyinToneType;
import net.sourceforge.pinyin4j.format.exception.BadHanyuPinyinOutputFormatCombination;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * 文件存储服务，统一封装 RustFS / S3 兼容对象存储操作。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FileStorageService {

    private static final DateTimeFormatter DATE_PATH_FORMAT =
            DateTimeFormatter.ofPattern("yyyy/MM/dd");

    private final S3Client s3Client;
    private final StorageConfigProperties storageConfig;

    public String uploadResume(MultipartFile file) {
        return uploadFile(file, "resumes");
    }

    public void deleteResume(String fileKey) {
        deleteFile(fileKey);
    }

    public String uploadKnowledgeBase(MultipartFile file) {
        return uploadFile(file, "knowledgebases");
    }

    public void deleteKnowledgeBase(String fileKey) {
        deleteFile(fileKey);
    }

    public byte[] downloadFile(String fileKey) {
        if (!fileExists(fileKey)) {
            throw new BusinessException(ErrorCode.STORAGE_DOWNLOAD_FAILED, "文件不存在: " + fileKey);
        }

        try {
            GetObjectRequest getRequest = GetObjectRequest.builder()
                    .bucket(storageConfig.getBucket())
                    .key(fileKey)
                    .build();
            return s3Client.getObjectAsBytes(getRequest).asByteArray();
        } catch (S3Exception e) {
            log.error(
                    "下载文件失败: fileKey={}, endpoint={}, bucket={}",
                    fileKey,
                    storageConfig.getEndpoint(),
                    storageConfig.getBucket(),
                    e
            );
            throw new BusinessException(
                    ErrorCode.STORAGE_DOWNLOAD_FAILED,
                    buildStorageErrorMessage("下载", e)
            );
        } catch (SdkClientException e) {
            log.error(
                    "连接对象存储下载文件失败: fileKey={}, endpoint={}, bucket={}",
                    fileKey,
                    storageConfig.getEndpoint(),
                    storageConfig.getBucket(),
                    e
            );
            throw new BusinessException(
                    ErrorCode.STORAGE_DOWNLOAD_FAILED,
                    buildClientErrorMessage("下载", e)
            );
        }
    }

    // 上传文件到RustFS
    private String uploadFile(MultipartFile file, String prefix) {
        String originalFilename = file.getOriginalFilename();
        String fileKey = generateFileKey(originalFilename, prefix); // 生成唯一 Key

        try {
            ensureBucketExists();

            // 构建S3上传请求对象
            PutObjectRequest putRequest = PutObjectRequest.builder()
                    .bucket(storageConfig.getBucket())
                    .key(fileKey)
                    .contentType(file.getContentType())
                    .contentLength(file.getSize())
                    .build();

            // 执行文件上传操作
            s3Client.putObject(
                    putRequest,
                    RequestBody.fromInputStream(file.getInputStream(), file.getSize())
            );
            log.info("文件上传成功: originalFilename={}, fileKey={}", originalFilename, fileKey);
            return fileKey;
        } catch (IOException e) {
            log.error("读取上传文件失败: originalFilename={}", originalFilename, e);
            throw new BusinessException(ErrorCode.STORAGE_UPLOAD_FAILED, "文件读取失败");
        } catch (S3Exception e) {
            log.error(
                    "上传文件到对象存储失败: originalFilename={}, endpoint={}, bucket={}, fileKey={}",
                    originalFilename,
                    storageConfig.getEndpoint(),
                    storageConfig.getBucket(),
                    fileKey,
                    e
            );
            throw new BusinessException(
                    ErrorCode.STORAGE_UPLOAD_FAILED,
                    buildStorageErrorMessage("上传", e)
            );
        } catch (SdkClientException e) {
            log.error(
                    "连接对象存储上传文件失败: originalFilename={}, endpoint={}, bucket={}, fileKey={}",
                    originalFilename,
                    storageConfig.getEndpoint(),
                    storageConfig.getBucket(),
                    fileKey,
                    e
            );
            throw new BusinessException(
                    ErrorCode.STORAGE_UPLOAD_FAILED,
                    buildClientErrorMessage("上传", e)
            );
        }
    }

    public boolean fileExists(String fileKey) {
        try {
            HeadObjectRequest headRequest = HeadObjectRequest.builder()
                    .bucket(storageConfig.getBucket())
                    .key(fileKey)
                    .build();
            s3Client.headObject(headRequest);
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        } catch (SdkClientException e) {
            log.warn(
                    "连接对象存储检查文件是否存在失败: fileKey={}, endpoint={}, bucket={}",
                    fileKey,
                    storageConfig.getEndpoint(),
                    storageConfig.getBucket(),
                    e
            );
            return false;
        } catch (S3Exception e) {
            log.warn(
                    "检查文件是否存在失败: fileKey={}, endpoint={}, bucket={}, message={}",
                    fileKey,
                    storageConfig.getEndpoint(),
                    storageConfig.getBucket(),
                    e.getMessage()
            );
            return false;
        }
    }

    private void deleteFile(String fileKey) {
        if (fileKey == null || fileKey.isEmpty()) {
            log.debug("文件 key 为空，跳过删除");
            return;
        }

        if (!fileExists(fileKey)) {
            log.warn("文件不存在，跳过删除: fileKey={}", fileKey);
            return;
        }

        try {
            DeleteObjectRequest deleteRequest = DeleteObjectRequest.builder()
                    .bucket(storageConfig.getBucket())
                    .key(fileKey)
                    .build();
            s3Client.deleteObject(deleteRequest);
            log.info("文件删除成功: fileKey={}", fileKey);
        } catch (S3Exception e) {
            log.error(
                    "删除文件失败: fileKey={}, endpoint={}, bucket={}",
                    fileKey,
                    storageConfig.getEndpoint(),
                    storageConfig.getBucket(),
                    e
            );
            throw new BusinessException(
                    ErrorCode.STORAGE_DELETE_FAILED,
                    buildStorageErrorMessage("删除", e)
            );
        } catch (SdkClientException e) {
            log.error(
                    "连接对象存储删除文件失败: fileKey={}, endpoint={}, bucket={}",
                    fileKey,
                    storageConfig.getEndpoint(),
                    storageConfig.getBucket(),
                    e
            );
            throw new BusinessException(
                    ErrorCode.STORAGE_DELETE_FAILED,
                    buildClientErrorMessage("删除", e)
            );
        }
    }

    public String getFileUrl(String fileKey) {
        return String.format(
                "%s/%s/%s",
                storageConfig.getEndpoint(),
                storageConfig.getBucket(),
                fileKey
        );
    }

    public void ensureBucketExists() {
        try {
            HeadBucketRequest headRequest = HeadBucketRequest.builder()
                    .bucket(storageConfig.getBucket())
                    .build();
            s3Client.headBucket(headRequest);
            log.info("存储桶已存在: bucket={}", storageConfig.getBucket());
        } catch (NoSuchBucketException e) {
            createBucket();
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                createBucket();
                return;
            }
            log.error(
                    "检查或创建存储桶失败: endpoint={}, bucket={}",
                    storageConfig.getEndpoint(),
                    storageConfig.getBucket(),
                    e
            );
            throw new BusinessException(
                    ErrorCode.STORAGE_UPLOAD_FAILED,
                    buildStorageErrorMessage("上传", e)
            );
        } catch (SdkClientException e) {
            log.error(
                    "连接对象存储失败: endpoint={}, bucket={}",
                    storageConfig.getEndpoint(),
                    storageConfig.getBucket(),
                    e
            );
            throw new BusinessException(
                    ErrorCode.STORAGE_UPLOAD_FAILED,
                    buildClientErrorMessage("上传", e)
            );
        }
    }

    private void createBucket() {
        log.info("存储桶不存在，开始创建: bucket={}", storageConfig.getBucket());
        try {
            CreateBucketRequest createRequest = CreateBucketRequest.builder()
                    .bucket(storageConfig.getBucket())
                    .build();
            s3Client.createBucket(createRequest);
            log.info("存储桶创建成功: bucket={}", storageConfig.getBucket());
        } catch (S3Exception e) {
            log.error(
                    "创建存储桶失败: endpoint={}, bucket={}",
                    storageConfig.getEndpoint(),
                    storageConfig.getBucket(),
                    e
            );
            throw new BusinessException(
                    ErrorCode.STORAGE_UPLOAD_FAILED,
                    buildStorageErrorMessage("上传", e)
            );
        } catch (SdkClientException e) {
            log.error(
                    "连接对象存储创建存储桶失败: endpoint={}, bucket={}",
                    storageConfig.getEndpoint(),
                    storageConfig.getBucket(),
                    e
            );
            throw new BusinessException(
                    ErrorCode.STORAGE_UPLOAD_FAILED,
                    buildClientErrorMessage("上传", e)
            );
        }
    }

    // 根据原始文件名和路径前缀生成唯一的文件存储key
    private String generateFileKey(String originalFilename, String prefix) {
        LocalDateTime now = LocalDateTime.now();
        String datePath = now.format(DATE_PATH_FORMAT);
        String uuid = UUID.randomUUID().toString().substring(0, 8);
        String safeName = sanitizeFilename(originalFilename);
        return String.format("%s/%s/%s_%s", prefix, datePath, uuid, safeName);
    }

    private String sanitizeFilename(String filename) {
        if (filename == null || filename.isEmpty()) {
            return "unknown";
        }
        return convertToPinyin(filename);
    }

    private String convertToPinyin(String input) {
        HanyuPinyinOutputFormat format = new HanyuPinyinOutputFormat();
        format.setCaseType(HanyuPinyinCaseType.LOWERCASE);
        format.setToneType(HanyuPinyinToneType.WITHOUT_TONE);

        StringBuilder result = new StringBuilder();
        for (char ch : input.toCharArray()) {
            try {
                String[] pinyins = PinyinHelper.toHanyuPinyinStringArray(ch, format);
                if (pinyins != null && pinyins.length > 0) {
                    result.append(capitalize(pinyins[0]));
                } else {
                    result.append(sanitizeChar(ch));
                }
            } catch (BadHanyuPinyinOutputFormatCombination e) {
                result.append(sanitizeChar(ch));
            }
        }
        return result.toString();
    }

    private char sanitizeChar(char ch) {
        if ((ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z')
                || (ch >= '0' && ch <= '9') || ch == '.' || ch == '_' || ch == '-') {
            return ch;
        }
        return '_';
    }

    private String capitalize(String str) {
        if (str == null || str.isEmpty()) {
            return str;
        }
        return str.substring(0, 1).toUpperCase() + str.substring(1);
    }

    private String buildStorageErrorMessage(String operation, S3Exception exception) {
        if (isCredentialError(exception)) {
            return String.format(
                    "对象存储%s认证失败，请检查 APP_STORAGE_ENDPOINT、APP_STORAGE_ACCESS_KEY、"
                            + "APP_STORAGE_SECRET_KEY 是否与当前 RustFS 实例一致: endpoint=%s, bucket=%s",
                    operation,
                    storageConfig.getEndpoint(),
                    storageConfig.getBucket()
            );
        }
        return "文件" + operation + "失败: " + exception.getMessage();
    }

    private String buildClientErrorMessage(String operation, SdkClientException exception) {
        return String.format(
                "对象存储%s连接失败，请确认 RustFS/S3 服务已启动且 APP_STORAGE_ENDPOINT "
                        + "配置正确: endpoint=%s, bucket=%s, detail=%s",
                operation,
                storageConfig.getEndpoint(),
                storageConfig.getBucket(),
                exception.getMessage()
        );
    }

    private boolean isCredentialError(S3Exception exception) {
        String errorCode = exception.awsErrorDetails() != null
                ? exception.awsErrorDetails().errorCode()
                : null;
        String message = exception.getMessage();
        return exception.statusCode() == 403
                || "InvalidAccessKeyId".equals(errorCode)
                || "SignatureDoesNotMatch".equals(errorCode)
                || (message != null && (
                message.contains("Access Key Id")
                        || message.contains("SignatureDoesNotMatch")
                        || message.contains("InvalidAccessKeyId")
        ));
    }
}
