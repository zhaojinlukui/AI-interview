package interview.guide.modules.interviewschedule.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.LocalDateTime;
import lombok.Data;

@Data
public class InterviewScheduleDTO {

  private Long id;                     // 主键 ID
  private String companyName;          // 公司名称
  private String position;             // 面试岗位

  @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
  private LocalDateTime interviewTime; // 面试时间

  private String interviewType;        // 面试形式
  private String meetingLink;          // 会议链接
  private Integer roundNumber;         // 面试轮次
  private String interviewer;          // 面试官
  private String notes;                // 备注
  private InterviewStatus status;      // 日程状态
  private LocalDateTime createdAt;     // 创建时间
  private LocalDateTime updatedAt;     // 更新时间
}
