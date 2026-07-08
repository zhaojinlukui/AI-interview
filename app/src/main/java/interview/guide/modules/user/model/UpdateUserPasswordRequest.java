package interview.guide.modules.user.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 管理员重置用户密码的请求
 */
public record UpdateUserPasswordRequest(
    @NotBlank(message = "新密码不能为空")
    @Size(min = 6, max = 128, message = "密码长度至少为6位")
    String newPassword
) {
}
