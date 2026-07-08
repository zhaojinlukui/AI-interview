package interview.guide.common.auth;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.user.model.UserRole;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 当前用户上下文工具类
 * 从请求属性中获取认证拦截器设置的用户信息，
 * 提供用户身份获取、管理员权限校验和资源归属校验等静态方法
 */
public final class CurrentUserContext {

    private CurrentUserContext() {}

    /**
     * 获取当前登录用户完整信息
     * 从请求属性中提取用户ID、用户名和角色，未登录时抛出异常
     */
    public static CurrentUser getRequiredUser() {
        HttpServletRequest request = currentRequest();
        Object userId = request.getAttribute("userId");
        Object username = request.getAttribute("username");
        Object role = request.getAttribute("userRole");
        // 任一属性为空说明用户未通过认证拦截器
        if (userId == null || username == null || role == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "请先登录");
        }
        return new CurrentUser(
                userId.toString(),
                username.toString(),
                UserRole.valueOf(role.toString())
        );
    }

    /**
     * 获取当前登录用户ID
     */
    public static String getRequiredUserId() {
        return getRequiredUser().userId();
    }

    /**
     * 获取当前用户ID，未登录时返回null而不抛出异常
     * 适用于可选登录的场景
     */
    public static String getCurrentUserIdOrNull() {
        ServletRequestAttributes attributes =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            return null;
        }
        Object userId = attributes.getRequest().getAttribute("userId");
        return userId == null ? null : userId.toString();
    }

    /**
     * 判断当前用户是否为管理员
     */
    public static boolean isAdmin() {
        return getRequiredUser().isAdmin();
    }

    /**
     * 获取当前HTTP请求对象
     */
    private static HttpServletRequest currentRequest() {
        ServletRequestAttributes attributes =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "请先登录");
        }
        return attributes.getRequest();
    }
}