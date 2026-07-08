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

/**
 * AI配置管理控制器
 * 管理大模型、语音识别和语音合成的配置信息，
 * 支持配置的查看、修改和连通性测试
 */
@RestController
@RequestMapping("/api/settings/ai")
@RequiredArgsConstructor
public class AiSettingsController {

    private final AiSettingsService aiSettingsService;

    // 获取大模型配置
    @GetMapping("/model")
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 30)
    public Result<ModelSettingsDTO> getModelSettings() {
        return Result.success(aiSettingsService.getModelSettings());
    }

    // 更新大模型配置
    @PutMapping("/model")
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 5)
    public Result<Void> updateModelSettings(@RequestBody @Valid ModelSettingsRequest request) {
        aiSettingsService.updateModelSettings(request);
        return Result.success();
    }

    // 测试大模型连通性 - 发送测试请求验证当前配置的大模型是否可用
    @PostMapping("/model/test")
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 10)
    public Result<SettingsTestResult> testModelSettings() {
        return Result.success(aiSettingsService.testModelSettings());
    }

    // 获取语音识别配置
    @GetMapping("/voice/asr")
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 30)
    public Result<AsrConfigDTO> getAsrConfig() {
        return Result.success(aiSettingsService.getAsrConfig());
    }

    // 更新语音识别配置
    @PutMapping("/voice/asr")
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 5)
    public Result<Void> updateAsrConfig(@RequestBody AsrConfigRequest request) {
        aiSettingsService.updateAsrConfig(request);
        return Result.success();
    }

    // 测试语音识别连通性
    @PostMapping("/voice/asr/test")
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 10)
    public Result<SettingsTestResult> testAsrConfig() {
        return Result.success(aiSettingsService.testAsrConfig());
    }

    // 获取语音合成配置
    @GetMapping("/voice/tts")
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 30)
    public Result<TtsConfigDTO> getTtsConfig() {
        return Result.success(aiSettingsService.getTtsConfig());
    }

    // 更新语音合成配置
    @PutMapping("/voice/tts")
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 5)
    public Result<Void> updateTtsConfig(@RequestBody TtsConfigRequest request) {
        aiSettingsService.updateTtsConfig(request);
        return Result.success();
    }
}