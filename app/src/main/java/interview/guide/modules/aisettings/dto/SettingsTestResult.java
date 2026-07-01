package interview.guide.modules.aisettings.dto;

import lombok.Builder;

@Builder
public record SettingsTestResult(
    boolean success, // 测试是否成功
    String message,  // 测试结果消息
    String model     // 实际测试模型
) {
}
