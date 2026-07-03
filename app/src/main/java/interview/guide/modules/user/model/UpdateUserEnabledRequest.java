package interview.guide.modules.user.model;

import jakarta.validation.constraints.NotNull;

/**
 * 管理员修改用户启用状态的请求。
 */
public record UpdateUserEnabledRequest(
    @NotNull(message = "启用状态不能为空")
    Boolean enabled
) {
}
