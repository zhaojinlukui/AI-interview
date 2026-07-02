package interview.guide.modules.user;

import interview.guide.common.annotation.RateLimit;
import interview.guide.common.result.Result;
import interview.guide.modules.user.model.AuthUserDTO;
import interview.guide.modules.user.model.ProfileStatsResponse;
import interview.guide.modules.user.model.UpdateCurrentUserPasswordRequest;
import interview.guide.modules.user.model.UpdateDisplayNameRequest;
import interview.guide.modules.user.service.ProfileService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户个人资料控制器
 * 处理用户个人资料相关的请求，包括查看统计信息、修改显示名称和密码
 */
@RestController
@RequestMapping("/api/profile")
@RequiredArgsConstructor
public class ProfileController {

    private final ProfileService profileService;

    // 获取当前用户的个人统计信息
    @GetMapping("/stats")
    public Result<ProfileStatsResponse> stats() {
        return Result.success(profileService.getStats());
    }

    // 更新当前用户的显示名称
    @PutMapping("/display-name")
    public Result<AuthUserDTO> updateDisplayName(@Valid @RequestBody UpdateDisplayNameRequest request) {
        return Result.success(profileService.updateDisplayName(request.displayName()));
    }

    // 修改当前用户的密码
    @PutMapping("/password")
    @RateLimit(dimension = RateLimit.Dimension.USER, count = 5)
    public Result<Void> updatePassword(@Valid @RequestBody UpdateCurrentUserPasswordRequest request) {
        profileService.updatePassword(request.currentPassword(), request.newPassword());
        return Result.success();
    }
}