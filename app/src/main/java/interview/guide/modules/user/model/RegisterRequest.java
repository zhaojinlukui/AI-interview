package interview.guide.modules.user.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
    @NotBlank(message = "用户名不能为空")
    String username,

    @NotBlank(message = "密码不能为空")
    @Size(min = 6, max = 128, message = "密码长度至少为6位")
    String password,

    @Size(max = 20, message = "昵称不能超过 20 个字符")
    String displayName
) {
}
