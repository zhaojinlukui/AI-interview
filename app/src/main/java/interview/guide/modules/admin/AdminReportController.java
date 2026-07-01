package interview.guide.modules.admin;

import interview.guide.common.result.Result;
import interview.guide.modules.admin.model.AdminInterviewItemDTO;
import interview.guide.modules.admin.model.AdminVoiceInterviewDetailDTO;
import interview.guide.modules.admin.service.AdminReportService;
import interview.guide.modules.interview.model.InterviewDetailDTO;
import interview.guide.modules.resume.model.ResumeDetailDTO;
import interview.guide.modules.resume.model.ResumeListItemDTO;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminReportController {

  private final AdminReportService adminReportService;

  @GetMapping("/users/{id}/resumes")
  public Result<List<ResumeListItemDTO>> listUserResumes(@PathVariable Long id) {
    return Result.success(adminReportService.listUserResumes(id));
  }

  @GetMapping("/resumes/{resumeId}/detail")
  public Result<ResumeDetailDTO> getResumeDetail(@PathVariable Long resumeId) {
    return Result.success(adminReportService.getResumeDetail(resumeId));
  }

  @GetMapping("/resumes/{resumeId}/export")
  public ResponseEntity<byte[]> exportResumeAnalysis(@PathVariable Long resumeId) {
    try {
      return toPdfResponse(adminReportService.exportResumeAnalysisPdf(resumeId));
    } catch (Exception e) {
      log.error("Admin resume PDF export failed: resumeId={}", resumeId, e);
      return ResponseEntity.internalServerError().build();
    }
  }

  @GetMapping("/users/{id}/interviews")
  public Result<List<AdminInterviewItemDTO>> listUserInterviews(@PathVariable Long id) {
    return Result.success(adminReportService.listUserInterviews(id));
  }

  @GetMapping("/interviews/text/{sessionId}")
  public Result<InterviewDetailDTO> getTextInterviewDetail(@PathVariable String sessionId) {
    return Result.success(adminReportService.getTextInterviewDetail(sessionId));
  }

  @GetMapping("/interviews/text/{sessionId}/export")
  public ResponseEntity<byte[]> exportTextInterview(@PathVariable String sessionId) {
    try {
      return toPdfResponse(adminReportService.exportTextInterviewPdf(sessionId));
    } catch (Exception e) {
      log.error("Admin text interview PDF export failed: sessionId={}", sessionId, e);
      return ResponseEntity.internalServerError().build();
    }
  }

  @GetMapping("/interviews/voice/{sessionId}")
  public Result<AdminVoiceInterviewDetailDTO> getVoiceInterviewDetail(@PathVariable Long sessionId) {
    return Result.success(adminReportService.getVoiceInterviewDetail(sessionId));
  }

  private ResponseEntity<byte[]> toPdfResponse(AdminReportService.ExportResult result) {
    String filename = URLEncoder.encode(result.filename(), StandardCharsets.UTF_8);
    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + filename)
        .contentType(MediaType.APPLICATION_PDF)
        .body(result.pdfBytes());
  }
}
