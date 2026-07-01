package interview.guide.common.async;

/**
 * 与消息中间件实现无关的异步任务消息标识。
 */
public record AsyncMessageId(String value) {

    @Override
    public String toString() {
        return value;
    }
}
