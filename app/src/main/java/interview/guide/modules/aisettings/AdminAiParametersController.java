package interview.guide.modules.aisettings;

import interview.guide.common.annotation.RateLimit;
import interview.guide.common.result.Result;
import interview.guide.modules.aisettings.dto.SystemAiParametersDTO;
import interview.guide.modules.aisettings.service.SystemAiSettingsService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理员AI参数配置控制器
 * 供管理员查看和修改系统级的AI全局参数
 */
@RestController
@RequestMapping("/api/admin/ai-parameters")
@RequiredArgsConstructor
public class AdminAiParametersController {

    private final SystemAiSettingsService settingsService;

    // 获取系统AI全局参数
    @GetMapping
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 30)
    public Result<SystemAiParametersDTO> getParameters() {
        return Result.success(settingsService.getParameters());
    }

    // 更新系统AI全局参数
    @PutMapping
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 10)
    public Result<Void> updateParameters(@RequestBody @Valid SystemAiParametersDTO request) {
        settingsService.updateParameters(request);
        return Result.success();
    }
}