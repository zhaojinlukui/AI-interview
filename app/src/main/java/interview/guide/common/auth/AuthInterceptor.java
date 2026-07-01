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

@Component
@RequiredArgsConstructor
public class AuthInterceptor implements HandlerInterceptor {

  private final JwtService jwtService;
  private final UserRepository userRepository;

  @Override
  public boolean preHandle(
      HttpServletRequest request,
      HttpServletResponse response,
      Object handler
  ) {
    if ("OPTIONS".equalsIgnoreCase(request.getMethod()) || isPublicPath(request.getRequestURI())) {
      return true;
    }

    String token = resolveBearerToken(request);
    JwtClaims claims = jwtService.parse(token);
    UserEntity user = userRepository.findById(claims.userId())
        .orElseThrow(() -> new BusinessException(ErrorCode.TOKEN_INVALID, "登录状态无效"));
    if (!Boolean.TRUE.equals(user.getEnabled())) {
      throw new BusinessException(ErrorCode.USER_DISABLED, "用户已被禁用");
    }
    if (user.getTokenVersion() != claims.tokenVersion()) {
      throw new BusinessException(ErrorCode.TOKEN_INVALID, "登录状态已失效，请重新登录");
    }

    request.setAttribute("userId", user.getId().toString());
    request.setAttribute("username", user.getUsername());
    request.setAttribute("userRole", user.getRole().name());

    if (requiresAdmin(request.getRequestURI()) && user.getRole() != UserRole.ADMIN) {
      throw new BusinessException(ErrorCode.FORBIDDEN, "仅管理员可访问");
    }
    return true;
  }

  private boolean isPublicPath(String uri) {
    return uri.equals("/api/auth/login") || uri.equals("/api/auth/register");
  }

  private boolean requiresAdmin(String uri) {
    return uri.startsWith("/api/admin/");
  }

  private String resolveBearerToken(HttpServletRequest request) {
    String header = request.getHeader("Authorization");
    if (header == null || !header.startsWith("Bearer ")) {
      throw new BusinessException(ErrorCode.UNAUTHORIZED, "请先登录");
    }
    return header.substring("Bearer ".length()).trim();
  }
}
