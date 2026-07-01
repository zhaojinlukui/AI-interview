package interview.guide.modules.aisettings;

import interview.guide.common.annotation.RateLimit;
import interview.guide.common.result.Result;
import interview.guide.modules.aisettings.dto.AsrConfigDTO;
import interview.guide.modules.aisettings.dto.AsrConfigRequest;
import interview.guide.modules.aisettings.dto.ModelSettingsDTO;
import interview.guide.modules.aisettings.dto.ModelSettingsRequest;
import interview.guide.modules.aisettings.dto.SettingsTestResult;
import interview.guide.modules.aisettings.dto.TtsConfigDTO;
import interview.guide.modules.aisettings.dto.TtsConfigRequest;
import interview.guide.modules.aisettings.service.AiSettingsService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/settings/ai")
@RequiredArgsConstructor
public class AiSettingsController {

    private final AiSettingsService aiSettingsService;

    @GetMapping("/model")
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 30)
    public Result<ModelSettingsDTO> getModelSettings() {
        return Result.success(aiSettingsService.getModelSettings());
    }

    @PutMapping("/model")
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 5)
    public Result<Void> updateModelSettings(@RequestBody @Valid ModelSettingsRequest request) {
        aiSettingsService.updateModelSettings(request);
        return Result.success();
    }

    @PostMapping("/model/test")
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 10)
    public Result<SettingsTestResult> testModelSettings() {
        return Result.success(aiSettingsService.testModelSettings());
    }

    @GetMapping("/voice/asr")
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 30)
    public Result<AsrConfigDTO> getAsrConfig() {
        return Result.success(aiSettingsService.getAsrConfig());
    }

    @PutMapping("/voice/asr")
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 5)
    public Result<Void> updateAsrConfig(@RequestBody AsrConfigRequest request) {
        aiSettingsService.updateAsrConfig(request);
        return Result.success();
    }

    @PostMapping("/voice/asr/test")
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 10)
    public Result<SettingsTestResult> testAsrConfig() {
        return Result.success(aiSettingsService.testAsrConfig());
    }

    @GetMapping("/voice/tts")
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 30)
    public Result<TtsConfigDTO> getTtsConfig() {
        return Result.success(aiSettingsService.getTtsConfig());
    }

    @PutMapping("/voice/tts")
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 5)
    public Result<Void> updateTtsConfig(@RequestBody TtsConfigRequest request) {
        aiSettingsService.updateTtsConfig(request);
        return Result.success();
    }
}
