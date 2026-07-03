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

/**
 * 管理员报表控制器
 * 提供管理员视角下的简历管理、面试记录查询和 PDF 导出的 REST API 接口
 * 所有接口均以 GET 方式提供，路径统一以 /api/admin 为前缀
 */
@Slf4j
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminReportController {

    private final AdminReportService adminReportService;

    // 获取指定用户的所有简历列表
    @GetMapping("/users/{id}/resumes")
    public Result<List<ResumeListItemDTO>> listUserResumes(@PathVariable Long id) {
        return Result.success(adminReportService.listUserResumes(id));
    }

    // 获取简历的详细信息
    @GetMapping("/resumes/{resumeId}/detail")
    public Result<ResumeDetailDTO> getResumeDetail(@PathVariable Long resumeId) {
        return Result.success(adminReportService.getResumeDetail(resumeId));
    }

    // 导出简历分析结果为 PDF 文件
    @GetMapping("/resumes/{resumeId}/export")
    public ResponseEntity<byte[]> exportResumeAnalysis(@PathVariable Long resumeId) {
        try {
            return toPdfResponse(adminReportService.exportResumeAnalysisPdf(resumeId));
        } catch (Exception e) {
            log.error("管理员简历导出失败：resumeId={}", resumeId, e);
            return ResponseEntity.internalServerError().build();
        }
    }

    // 获取指定用户的所有面试记录
    @GetMapping("/users/{id}/interviews")
    public Result<List<AdminInterviewItemDTO>> listUserInterviews(@PathVariable Long id) {
        return Result.success(adminReportService.listUserInterviews(id));
    }

    // 获取文本面试的详细信息
    @GetMapping("/interviews/text/{sessionId}")
    public Result<InterviewDetailDTO> getTextInterviewDetail(@PathVariable String sessionId) {
        return Result.success(adminReportService.getTextInterviewDetail(sessionId));
    }

    // 导出文本面试报告为 PDF 文件
    @GetMapping("/interviews/text/{sessionId}/export")
    public ResponseEntity<byte[]> exportTextInterview(@PathVariable String sessionId) {
        try {
            return toPdfResponse(adminReportService.exportTextInterviewPdf(sessionId));
        } catch (Exception e) {
            log.error("管理员导出文本面试报告失败: sessionId={}", sessionId, e);
            return ResponseEntity.internalServerError().build();
        }
    }

    // 获取语音面试的详细信息
    @GetMapping("/interviews/voice/{sessionId}")
    public Result<AdminVoiceInterviewDetailDTO> getVoiceInterviewDetail(@PathVariable Long sessionId) {
        return Result.success(adminReportService.getVoiceInterviewDetail(sessionId));
    }

    // 将导出结果转换为 PDF 下载的响应实体
    private ResponseEntity<byte[]> toPdfResponse(AdminReportService.ExportResult result) {
        String filename = URLEncoder.encode(result.filename(), StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + filename)
                .contentType(MediaType.APPLICATION_PDF)
                .body(result.pdfBytes());
    }
}