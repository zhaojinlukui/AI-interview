package interview.guide.common.ai;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;

import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.util.regex.Pattern;

/**
 * API路径解析器
 * 负责构建OpenAiApi实例，自动检测baseUrl是否已包含版本路径（如/v1），
 * 若已包含则使用简化的端点路径，避免路径重复拼接
 */
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public final class ApiPathResolver {

    private static final int DEFAULT_CONNECT_TIMEOUT = 10000; // 默认连接超时（毫秒）
    private static final int DEFAULT_READ_TIMEOUT = 300000; // 默认读取超时（毫秒，5分钟）

    // 匹配尾部版本号路径，如/v1、/v2beta
    private static final Pattern TRAILING_VERSION = Pattern.compile("/v\\d+[a-zA-Z0-9]*$");

    /**
     * 构建OpenAiApi实例（使用默认超时）
     */
    public static OpenAiApi buildOpenAiApi(String baseUrl, String apiKey) {
        return buildOpenAiApi(baseUrl, apiKey, DEFAULT_CONNECT_TIMEOUT, DEFAULT_READ_TIMEOUT);
    }

    /**
     * 构建OpenAiApi实例（指定超时）
     * 自动检测baseUrl是否包含版本路径，若已包含则使用简化端点路径
     */
    public static OpenAiApi buildOpenAiApi(String baseUrl, String apiKey,
                                           int connectTimeout, int readTimeout) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(connectTimeout);
        requestFactory.setReadTimeout(readTimeout);

        RestClient.Builder restClientBuilder = RestClient.builder()
                .requestFactory(requestFactory);

        OpenAiApi.Builder apiBuilder = OpenAiApi.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .restClientBuilder(restClientBuilder);
        // baseUrl已包含版本路径时，使用简化端点避免路径重复（如/v1/v1/chat/completions）
        if (baseUrlContainsVersion(baseUrl)) {
            apiBuilder.completionsPath("/chat/completions").embeddingsPath("/embeddings");
        }
        return apiBuilder.build();
    }

    /**
     * 判断baseUrl是否已包含版本路径
     * 如https://api.openai.com/v1返回true
     */
    public static boolean baseUrlContainsVersion(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return false;
        }
        String stripped = stripTrailingSlashes(baseUrl.trim());
        return TRAILING_VERSION.matcher(stripped).find();
    }

    /**
     * 去除URL尾部的斜杠
     */
    public static String stripTrailingSlashes(String value) {
        if (value == null) {
            return "";
        }
        String result = value.trim();
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }
}