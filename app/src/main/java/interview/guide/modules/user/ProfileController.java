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

@RestController
@RequestMapping("/api/profile")
@RequiredArgsConstructor
public class ProfileController {

    private final ProfileService profileService;

    @GetMapping("/stats")
    public Result<ProfileStatsResponse> stats() {
        return Result.success(profileService.getStats());
    }

    @PutMapping("/display-name")
    public Result<AuthUserDTO> updateDisplayName(
            @Valid @RequestBody UpdateDisplayNameRequest request
    ) {
        return Result.success(profileService.updateDisplayName(request.displayName()));
    }

    @PutMapping("/password")
    @RateLimit(dimension = RateLimit.Dimension.USER, count = 5)
    public Result<Void> updatePassword(
            @Valid @RequestBody UpdateCurrentUserPasswordRequest request
    ) {
        profileService.updatePassword(request.currentPassword(), request.newPassword());
        return Result.success();
    }
}
