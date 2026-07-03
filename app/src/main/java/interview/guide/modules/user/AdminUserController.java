package interview.guide.modules.user;

import interview.guide.common.result.Result;
import interview.guide.modules.user.model.AdminUserDTO;
import interview.guide.modules.user.model.UpdateDisplayNameRequest;
import interview.guide.modules.user.model.UpdateUserEnabledRequest;
import interview.guide.modules.user.model.UpdateUserPasswordRequest;
import interview.guide.modules.user.service.AdminUserService;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理员用户管理控制器
 * 提供管理员对用户的管理功能，包括查看用户列表、修改用户密码、显示名称和账号状态
 */
@RestController
@RequestMapping("/api/admin/users")
@RequiredArgsConstructor
public class AdminUserController {

    private final AdminUserService adminUserService;

    // 获取所有用户列表
    @GetMapping
    public Result<List<AdminUserDTO>> listUsers() {
        return Result.success(adminUserService.listUsers());
    }

    // 修改指定用户的密码
    @PutMapping("/{id}/password")
    public Result<Void> updatePassword(
            @PathVariable Long id,
            @Valid @RequestBody UpdateUserPasswordRequest request
    ) {
        adminUserService.updatePassword(id, request.newPassword());
        return Result.success();
    }

    // 修改指定用户的显示名称
    @PutMapping("/{id}/display-name")
    public Result<Void> updateDisplayName(
            @PathVariable Long id,
            @Valid @RequestBody UpdateDisplayNameRequest request
    ) {
        adminUserService.updateDisplayName(id, request.displayName());
        return Result.success();
    }

    // 修改指定用户的启用状态
    @PutMapping("/{id}/enabled")
    public Result<Void> updateEnabled(
            @PathVariable Long id,
            @Valid @RequestBody UpdateUserEnabledRequest request
    ) {
        adminUserService.updateEnabled(id, request.enabled());
        return Result.success();
    }

    // 删除指定用户账号
    @DeleteMapping("/{id}")
    public Result<Void> deleteUser(@PathVariable Long id) {
        adminUserService.deleteUser(id);
        return Result.success();
    }
}
