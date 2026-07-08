package interview.guide.modules.interviewschedule.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ParseResponse {

  private Boolean success;             // 是否解析成功
  private CreateInterviewRequest data; // 解析出的面试信息
  private String parseMethod;          // 解析方式（rule/ai/none）
  private String log;                  // 解析日志
}
