package interview.guide.common.constant;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;

/**
 * 通用常量定义
 */
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public final class CommonConstants {
    
    /**
     * 状态码
     */
    @RequiredArgsConstructor(access = AccessLevel.PRIVATE)
    public static final class StatusCode {
        public static final int SUCCESS = 200;
        public static final int BAD_REQUEST = 400;
        public static final int UNAUTHORIZED = 401;
        public static final int FORBIDDEN = 403;
        public static final int NOT_FOUND = 404;
        public static final int SERVER_ERROR = 500;
    }
    
    /**
     * 分页默认值
     */
    @RequiredArgsConstructor(access = AccessLevel.PRIVATE)
    public static final class Pagination {
        public static final int DEFAULT_PAGE = 1;
        public static final int DEFAULT_SIZE = 20;
        public static final int MAX_SIZE = 100;
    }

    /**
     * 面试默认值
     */
    @RequiredArgsConstructor(access = AccessLevel.PRIVATE)
    public static final class InterviewDefaults {
        public static final String SKILL_ID = "java-backend";
        public static final String DIFFICULTY = "mid";
    }
}
