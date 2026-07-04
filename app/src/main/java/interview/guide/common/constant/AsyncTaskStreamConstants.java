package interview.guide.common.constant;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public final class AsyncTaskStreamConstants {

    public static final String FIELD_RETRY_COUNT = "retryCount";
    public static final String FIELD_CONTENT = "content";

    public static final int MAX_RETRY_COUNT = 3;
    public static final int STREAM_MAX_LEN = 1000;

    public static final String RABBITMQ_EXCHANGE_NAME = "interview.async.tasks.exchange";

    public static final String KB_VECTORIZE_STREAM_KEY = "knowledgebase:vectorize:stream";
    public static final String KB_VECTORIZE_GROUP_NAME = "vectorize-group";
    public static final String KB_VECTORIZE_CONSUMER_PREFIX = "vectorize-consumer-";
    public static final String FIELD_KB_ID = "kbId";

    public static final String RESUME_ANALYZE_STREAM_KEY = "resume:analyze:stream";
    public static final String RESUME_ANALYZE_GROUP_NAME = "analyze-group";
    public static final String RESUME_ANALYZE_CONSUMER_PREFIX = "analyze-consumer-";
    public static final String FIELD_RESUME_ID = "resumeId";

    public static final String INTERVIEW_EVALUATE_STREAM_KEY = "interview:evaluate:stream";
    public static final String INTERVIEW_EVALUATE_GROUP_NAME = "evaluate-group";
    public static final String INTERVIEW_EVALUATE_CONSUMER_PREFIX = "evaluate-consumer-";
    public static final String FIELD_SESSION_ID = "sessionId";

    public static final String VOICE_EVALUATE_STREAM_KEY = "voice:evaluate:stream";
    public static final String VOICE_EVALUATE_GROUP_NAME = "voice-evaluate-group";
    public static final String VOICE_EVALUATE_CONSUMER_PREFIX = "voice-evaluate-consumer-";
    public static final String FIELD_VOICE_SESSION_ID = "voiceSessionId";
}
