package interview.guide.common.auth;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.user.model.UserEntity;
import interview.guide.modules.user.model.UserRole;
import interview.guide.modules.user.repository.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 认证拦截器
 * 在请求进入控制器前进行身份认证和权限校验，
 * 放行公开路径和OPTIONS预检请求，其余请求需携带有效的Bearer令牌，
 * 管理员接口额外校验管理员角色
 */
@Component
@RequiredArgsConstructor
public class AuthInterceptor implements HandlerInterceptor {

    private final JwtService jwtService; // JWT令牌服务
    private final UserRepository userRepository; // 用户数据访问层

    /**
     * 请求前置处理
     * 依次进行：公开路径放行 → 令牌解析 → 用户状态校验 → 令牌版本校验 → 管理员权限校验
     */
    @Override
    public boolean preHandle(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler
    ) {
        // 放行OPTIONS预检请求和公开路径（登录、注册）
        if ("OPTIONS".equalsIgnoreCase(request.getMethod()) || isPublicPath(request.getRequestURI())) {
            return true;
        }

        // 从请求头提取Bearer令牌并解析
        String token = resolveBearerToken(request);
        JwtClaims claims = jwtService.parse(token);
        // 根据令牌中的用户ID查询用户，验证用户是否存在
        UserEntity user = userRepository.findById(claims.userId())
                .orElseThrow(() -> new BusinessException(ErrorCode.TOKEN_INVALID, "登录状态无效"));
        // 检查用户是否被禁用
        if (!Boolean.TRUE.equals(user.getEnabled())) {
            throw new BusinessException(ErrorCode.USER_DISABLED, "用户已被禁用");
        }
        // 验证令牌版本是否与用户当前版本一致（密码修改后旧令牌自动失效）
        if (user.getTokenVersion() != claims.tokenVersion()) {
            throw new BusinessException(ErrorCode.TOKEN_INVALID, "登录状态已失效，请重新登录");
        }

        // 将用户信息设置到请求属性中，供后续处理使用
        request.setAttribute("userId", user.getId().toString());
        request.setAttribute("username", user.getUsername());
        request.setAttribute("userRole", user.getRole().name());

        // 管理员接口额外校验角色
        if (requiresAdmin(request.getRequestURI()) && user.getRole() != UserRole.ADMIN) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "仅管理员可访问");
        }
        return true;
    }

    /**
     * 判断是否为公开路径（无需认证）
     */
    private boolean isPublicPath(String uri) {
        return uri.equals("/api/auth/login") || uri.equals("/api/auth/register");
    }

    /**
     * 判断是否为管理员接口
     */
    private boolean requiresAdmin(String uri) {
        return uri.startsWith("/api/admin/");
    }

    /**
     * 从请求头中提取Bearer令牌
     * 格式：Authorization: Bearer <token>
     */
    private String resolveBearerToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "请先登录");
        }
        // 去除"Bearer "前缀并返回令牌
        return header.substring("Bearer ".length()).trim();
    }
}