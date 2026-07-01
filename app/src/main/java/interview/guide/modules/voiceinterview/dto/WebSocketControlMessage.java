package interview.guide.modules.voiceinterview.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@JsonIgnoreProperties(ignoreUnknown = true)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WebSocketControlMessage {

  private String type;              // 消息类型（control）
  private String action;            // 控制动作
  private String phase;             // 面试阶段
  private Map<String, Object> data; // 控制载荷
}
