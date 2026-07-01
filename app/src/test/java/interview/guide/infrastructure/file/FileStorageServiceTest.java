package interview.guide.infrastructure.file;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import interview.guide.common.config.StorageConfigProperties;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.CreateBucketResponse;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;

class FileStorageServiceTest {

  private final S3Client s3Client = mock(S3Client.class);
  private final StorageConfigProperties storageConfig = buildStorageConfig();
  private final FileStorageService fileStorageService =
      new FileStorageService(s3Client, storageConfig);

  @Nested
  @DisplayName("上传简历")
  class UploadResume {

    @Test
    @DisplayName("对象存储连接失败时返回明确的业务异常")
    void shouldReturnBusinessExceptionWhenStorageConnectionFails() {
      MockMultipartFile file = resumeFile();
      when(s3Client.headBucket(any(HeadBucketRequest.class)))
          .thenThrow(SdkClientException.builder().message("Connection refused").build());

      assertThatThrownBy(() -> fileStorageService.uploadResume(file))
          .isInstanceOfSatisfying(BusinessException.class, exception -> {
            BusinessException businessException = (BusinessException) exception;
            assertThat(businessException.getCode())
                .isEqualTo(ErrorCode.STORAGE_UPLOAD_FAILED.getCode());
            assertThat(businessException.getMessage())
                .contains("对象存储上传连接失败")
                .contains("http://localhost:9000");
          });
    }

    @Test
    @DisplayName("存储桶不存在时先创建桶再上传文件")
    void shouldCreateBucketWhenBucketNotFound() {
      MockMultipartFile file = resumeFile();
      when(s3Client.headBucket(any(HeadBucketRequest.class)))
          .thenThrow(S3Exception.builder().statusCode(404).message("Not Found").build());
      when(s3Client.createBucket(any(CreateBucketRequest.class)))
          .thenReturn(CreateBucketResponse.builder().build());
      when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
          .thenReturn(PutObjectResponse.builder().build());

      String fileKey = fileStorageService.uploadResume(file);

      assertThat(fileKey).startsWith("resumes/");
      assertThat(fileKey).endsWith("_sample_resume.txt");
      verify(s3Client).createBucket(any(CreateBucketRequest.class));
      verify(s3Client).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    @Test
    @DisplayName("存储桶存在时直接上传文件")
    void shouldUploadWhenBucketExists() {
      MockMultipartFile file = resumeFile();
      when(s3Client.headBucket(any(HeadBucketRequest.class)))
          .thenReturn(HeadBucketResponse.builder().build());
      when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
          .thenReturn(PutObjectResponse.builder().build());

      String fileKey = fileStorageService.uploadResume(file);

      assertThat(fileKey).startsWith("resumes/");
      verify(s3Client).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }
  }

  private static MockMultipartFile resumeFile() {
    return new MockMultipartFile(
        "file",
        "sample_resume.txt",
        "text/plain",
        "Java backend developer".getBytes()
    );
  }

  private static StorageConfigProperties buildStorageConfig() {
    StorageConfigProperties properties = new StorageConfigProperties();
    properties.setEndpoint("http://localhost:9000");
    properties.setAccessKey("minioadmin");
    properties.setSecretKey("minioadmin");
    properties.setBucket("interview-guide");
    properties.setRegion("us-east-1");
    return properties;
  }
}
