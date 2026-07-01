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

@RestController
@RequestMapping("/api/admin/ai-parameters")
@RequiredArgsConstructor
public class AdminAiParametersController {

    private final SystemAiSettingsService settingsService;

    @GetMapping
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 30)
    public Result<SystemAiParametersDTO> getParameters() {
        return Result.success(settingsService.getParameters());
    }

    @PutMapping
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 10)
    public Result<Void> updateParameters(@RequestBody @Valid SystemAiParametersDTO request) {
        settingsService.updateParameters(request);
        return Result.success();
    }
}
