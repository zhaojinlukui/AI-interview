package interview.guide.common.ai;

import java.util.function.Supplier;

public final class AiSettingsUserContext {

  private static final ThreadLocal<String> CURRENT_USER_ID = new ThreadLocal<>();

  private AiSettingsUserContext() {
  }

  public static String getUserIdOrNull() {
    return CURRENT_USER_ID.get();
  }

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
      if (previous == null) {
        CURRENT_USER_ID.remove();
      } else {
        CURRENT_USER_ID.set(previous);
      }
    }
  }

  public static void run(String userId, Runnable runnable) {
    call(userId, () -> {
      runnable.run();
      return null;
    });
  }
}
