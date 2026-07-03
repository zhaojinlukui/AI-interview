package interview.guide.common.auth;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.user.model.UserEntity;
import interview.guide.modules.user.model.UserRole;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 轻量级HMAC JWT服务
 * 自实现JWT令牌的创建与解析，避免引入完整安全框架改变现有MVC行为，
 * 使用HmacSHA256算法签名，令牌包含用户ID、用户名、角色和令牌版本等信息
 */
@Service
@RequiredArgsConstructor
public class JwtService {

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding(); // URL安全的Base64编码器（无填充）
    private static final Base64.Decoder URL_DECODER = Base64.getUrlDecoder(); // URL安全的Base64解码器

    private final AuthProperties authProperties;
    private final ObjectMapper objectMapper;

    /**
     * 为用户创建JWT令牌
     * 令牌格式：Header.Payload.Signature（三段Base64编码，用.分隔）
     */
    public TokenResult createToken(UserEntity user) {
        // 计算过期时间戳（秒）
        long expiresAt = Instant.now()
                .plusSeconds(authProperties.getAccessTokenTtlMinutes() * 60)
                .getEpochSecond();
        // 构建JWT头部：算法和类型
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("alg", "HS256");
        header.put("typ", "JWT");

        // 构建JWT载荷：用户标识、用户名、角色、令牌版本、过期时间
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sub", user.getId().toString());
        payload.put("username", user.getUsername());
        payload.put("role", user.getRole().name());
        payload.put("ver", user.getTokenVersion());
        payload.put("exp", expiresAt);

        // 编码头部和载荷
        String headerPart = encodeJson(header);
        String payloadPart = encodeJson(payload);
        // 对头部和载荷进行签名
        String signature = sign(headerPart + "." + payloadPart);
        // 拼接完整令牌：header.payload.signature
        return new TokenResult(headerPart + "." + payloadPart + "." + signature, expiresAt);
    }

    /**
     * 解析并验证JWT令牌
     * 依次验证签名、过期时间，提取载荷中的用户信息
     */
    public JwtClaims parse(String token) {
        if (token == null || token.isBlank()) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "请先登录");
        }

        // 按.分割令牌为三段
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            throw new BusinessException(ErrorCode.TOKEN_INVALID, "登录状态无效");
        }

        // 重新计算签名并验证（使用恒定时间比较防止时序攻击）
        String expectedSignature = sign(parts[0] + "." + parts[1]);
        if (!constantTimeEquals(expectedSignature, parts[2])) {
            throw new BusinessException(ErrorCode.TOKEN_INVALID, "登录状态无效");
        }

        try {
            // 解码载荷并反序列化为Map
            Map<String, Object> payload = objectMapper.readValue(
                    new String(URL_DECODER.decode(parts[1]), StandardCharsets.UTF_8),
                    new TypeReference<>() {
                    }
            );
            // 检查令牌是否过期
            long expiresAt = toLong(payload.get("exp"));
            if (expiresAt < Instant.now().getEpochSecond()) {
                throw new BusinessException(ErrorCode.TOKEN_EXPIRED, "登录状态已过期，请重新登录");
            }
            // 提取载荷字段并返回
            return new JwtClaims(
                    Long.valueOf(payload.get("sub").toString()),
                    payload.get("username").toString(),
                    UserRole.valueOf(payload.get("role").toString()),
                    (int) toLong(payload.get("ver")),
                    expiresAt
            );
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.TOKEN_INVALID, "登录状态无效", e);
        }
    }

    /**
     * 将Map序列化为JSON并使用URL安全的Base64编码
     */
    private String encodeJson(Map<String, Object> value) {
        try {
            return URL_ENCODER.encodeToString(objectMapper.writeValueAsBytes(value));
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "生成登录凭证失败", e);
        }
    }

    /**
     * 使用HmacSHA256对内容进行签名并Base64编码
     */
    private String sign(String content) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            // 使用配置的密钥初始化HMAC
            mac.init(new SecretKeySpec(
                    authProperties.getTokenSecret().getBytes(StandardCharsets.UTF_8),
                    HMAC_ALGORITHM
            ));
            return URL_ENCODER.encodeToString(mac.doFinal(content.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "生成登录签名失败", e);
        }
    }

    /**
     * 恒定时间比较两个字符串
     * 逐字节异或比较，避免提前返回导致的时序攻击漏洞
     */
    private boolean constantTimeEquals(String expected, String actual) {
        byte[] expectedBytes = expected.getBytes(StandardCharsets.UTF_8);
        byte[] actualBytes = actual.getBytes(StandardCharsets.UTF_8);
        // 长度不同直接返回false
        if (expectedBytes.length != actualBytes.length) {
            return false;
        }
        // 逐字节异或并累积差异
        int diff = 0;
        for (int i = 0; i < expectedBytes.length; i++) {
            diff |= expectedBytes[i] ^ actualBytes[i];
        }
        // 差异为零表示完全相等
        return diff == 0;
    }

    /**
     * 将对象转换为long类型
     * 支持Number类型和字符串类型
     */
    private long toLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(value.toString());
    }

    /**
     * 令牌创建结果
     * 包含完整令牌字符串和过期时间戳（秒）
     */
    public record TokenResult(String token, long expiresAtEpochSecond) {
    }
}