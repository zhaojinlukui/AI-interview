package interview.guide.modules.interviewschedule.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDateTime;
import lombok.Data;

@Data
public class CreateInterviewRequest {

  @NotBlank(message = "公司名称不能为空")
  private String companyName;          // 公司名称

  @NotBlank(message = "岗位不能为空")
  private String position;             // 面试岗位

  @NotNull(message = "面试时间不能为空")
  @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm[:ss]")
  private LocalDateTime interviewTime; // 面试时间

  private String interviewType;        // 面试形式（ONSITE/VIDEO/PHONE）

  private String meetingLink;          // 会议链接

  private Integer roundNumber = 1;     // 面试轮次

  private String interviewer;          // 面试官

  private String notes;                // 备注
}
