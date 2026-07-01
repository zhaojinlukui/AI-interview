package interview.guide.common.auth;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.user.model.UserRole;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

public final class CurrentUserContext {

  private CurrentUserContext() {
  }

  public static CurrentUser getRequiredUser() {
    HttpServletRequest request = currentRequest();
    Object userId = request.getAttribute("userId");
    Object username = request.getAttribute("username");
    Object role = request.getAttribute("userRole");
    if (userId == null || username == null || role == null) {
      throw new BusinessException(ErrorCode.UNAUTHORIZED, "请先登录");
    }
    return new CurrentUser(
        userId.toString(),
        username.toString(),
        UserRole.valueOf(role.toString())
    );
  }

  public static String getRequiredUserId() {
    return getRequiredUser().userId();
  }

  public static String getCurrentUserIdOrNull() {
    ServletRequestAttributes attributes =
        (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
    if (attributes == null) {
      return null;
    }
    Object userId = attributes.getRequest().getAttribute("userId");
    return userId == null ? null : userId.toString();
  }

  public static boolean isAdmin() {
    return getRequiredUser().isAdmin();
  }

  public static void requireAdmin() {
    if (!isAdmin()) {
      throw new BusinessException(ErrorCode.FORBIDDEN, "仅管理员可访问");
    }
  }

  public static void requireOwner(String ownerUserId) {
    CurrentUser user = getRequiredUser();
    if (!user.userId().equals(ownerUserId)) {
      throw new BusinessException(ErrorCode.NOT_FOUND, "资源不存在");
    }
  }

  private static HttpServletRequest currentRequest() {
    ServletRequestAttributes attributes =
        (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
    if (attributes == null) {
      throw new BusinessException(ErrorCode.UNAUTHORIZED, "请先登录");
    }
    return attributes.getRequest();
  }
}
