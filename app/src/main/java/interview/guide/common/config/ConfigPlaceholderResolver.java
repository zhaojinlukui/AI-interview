package interview.guide.common.config;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 解析 ${KEY} 或 ${KEY:default} 占位符。
 * 优先从 JVM 系统属性和进程环境变量中取值。
 */
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public final class ConfigPlaceholderResolver {

    private static final Pattern PLACEHOLDER_PATTERN =
            Pattern.compile("\\$\\{([^:{}]+)(?::([^}]*))?}");

    public static String resolve(String value) {
        if (value == null || value.isBlank() || !value.contains("${")) {
            return value;
        }

        Matcher matcher = PLACEHOLDER_PATTERN.matcher(value);
        StringBuffer resolved = new StringBuffer();
        boolean matched = false;

        while (matcher.find()) {
            matched = true;
            String key = matcher.group(1);
            String defaultValue = matcher.group(2);
            String replacement = resolveValue(key, defaultValue);

            if (replacement == null) {
                matcher.appendReplacement(resolved, Matcher.quoteReplacement(matcher.group(0)));
            } else {
                matcher.appendReplacement(resolved, Matcher.quoteReplacement(replacement));
            }
        }

        if (!matched) {
            return value;
        }

        matcher.appendTail(resolved);
        return resolved.toString();
    }

    private static String resolveValue(String key, String defaultValue) {
        String systemProperty = trimToNull(System.getProperty(key));
        if (systemProperty != null) {
            return systemProperty;
        }

        String environmentValue = trimToNull(System.getenv(key));
        if (environmentValue != null) {
            return environmentValue;
        }

        return defaultValue;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }
}
