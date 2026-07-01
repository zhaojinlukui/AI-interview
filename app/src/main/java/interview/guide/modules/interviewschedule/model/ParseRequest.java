package interview.guide.modules.interviewschedule.model;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ParseRequest {

  @NotBlank(message = "文本不能为空")
  private String rawText; // 原始邀约文本

  private String source;  // 来源平台（feishu/tencent/zoom/other）
}
