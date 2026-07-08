package interview.guide.common.ai;

import java.util.function.Supplier;

/**
 * AI配置用户上下文工具类
 * 使用ThreadLocal存储当前线程的用户ID，用于在异步任务中传递用户身份，
 * 确保AI客户端工厂能根据正确的用户ID加载对应的配置
 */
public final class AiSettingsUserContext {

    private static final ThreadLocal<String> CURRENT_USER_ID = new ThreadLocal<>(); // 当前线程的用户ID

    private AiSettingsUserContext() {
    }

    /**
     * 获取当前线程的用户ID
     * 未设置时返回null
     */
    public static String getUserIdOrNull() {
        return CURRENT_USER_ID.get();
    }

    /**
     * 在指定用户上下文中执行带返回值的操作
     * 执行前设置用户ID，执行后恢复原来的用户ID，
     * 确保异步线程能使用正确的用户配置，同时不影响调用方的上下文
     *
     * @param userId 用户ID，为null或空时清除上下文
     * @param supplier 待执行的操作
     * @return 操作返回值
     */
    public static <T> T call(String userId, Supplier<T> supplier) {
        String previous = CURRENT_USER_ID.get();
        if (userId == null || userId.isBlank()) {
            CURRENT_USER_ID.remove();
        } else {
            CURRENT_USER_ID.set(userId);
        }
        try {
            return supplier.get();
        } finally {
            // 恢复原来的用户上下文
            if (previous == null) {
                CURRENT_USER_ID.remove();
            } else {
                CURRENT_USER_ID.set(previous);
            }
        }
    }
}