package interview.guide.modules.user;

import interview.guide.common.annotation.RateLimit;
import interview.guide.common.result.Result;
import interview.guide.modules.user.model.AuthResponse;
import interview.guide.modules.user.model.AuthUserDTO;
import interview.guide.modules.user.model.LoginRequest;
import interview.guide.modules.user.model.RegisterRequest;
import interview.guide.modules.user.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户认证控制器
 * 处理用户注册、登录及获取当前用户信息等认证相关请求
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    // 用户注册
    @PostMapping("/register")
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 10)
    @RateLimit(dimension = RateLimit.Dimension.IP, count = 5)
    public Result<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
        return Result.success(authService.register(request));
    }

    // 用户登录
    @PostMapping("/login")
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 30)
    @RateLimit(dimension = RateLimit.Dimension.IP, count = 10)
    public Result<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        return Result.success(authService.login(request));
    }

    // 获取当前登录用户信息
    @GetMapping("/me")
    public Result<AuthUserDTO> me() {
        return Result.success(authService.getCurrentUser());
    }
}