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
 * 轻量级 HMAC JWT 服务，避免引入完整安全框架改变现有 MVC 行为。
 */
@Service
@RequiredArgsConstructor
public class JwtService {

  private static final String HMAC_ALGORITHM = "HmacSHA256";
  private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();
  private static final Base64.Decoder URL_DECODER = Base64.getUrlDecoder();

  private final AuthProperties authProperties;
  private final ObjectMapper objectMapper;

  public TokenResult createToken(UserEntity user) {
    long expiresAt = Instant.now()
        .plusSeconds(authProperties.getAccessTokenTtlMinutes() * 60)
        .getEpochSecond();
    Map<String, Object> header = new LinkedHashMap<>();
    header.put("alg", "HS256");
    header.put("typ", "JWT");

    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("sub", user.getId().toString());
    payload.put("username", user.getUsername());
    payload.put("role", user.getRole().name());
    payload.put("ver", user.getTokenVersion());
    payload.put("exp", expiresAt);

    String headerPart = encodeJson(header);
    String payloadPart = encodeJson(payload);
    String signature = sign(headerPart + "." + payloadPart);
    return new TokenResult(headerPart + "." + payloadPart + "." + signature, expiresAt);
  }

  public JwtClaims parse(String token) {
    if (token == null || token.isBlank()) {
      throw new BusinessException(ErrorCode.UNAUTHORIZED, "请先登录");
    }

    String[] parts = token.split("\\.");
    if (parts.length != 3) {
      throw new BusinessException(ErrorCode.TOKEN_INVALID, "登录状态无效");
    }

    String expectedSignature = sign(parts[0] + "." + parts[1]);
    if (!constantTimeEquals(expectedSignature, parts[2])) {
      throw new BusinessException(ErrorCode.TOKEN_INVALID, "登录状态无效");
    }

    try {
      Map<String, Object> payload = objectMapper.readValue(
          new String(URL_DECODER.decode(parts[1]), StandardCharsets.UTF_8),
          new TypeReference<>() {
          }
      );
      long expiresAt = toLong(payload.get("exp"));
      if (expiresAt < Instant.now().getEpochSecond()) {
        throw new BusinessException(ErrorCode.TOKEN_EXPIRED, "登录状态已过期，请重新登录");
      }
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

  private String encodeJson(Map<String, Object> value) {
    try {
      return URL_ENCODER.encodeToString(objectMapper.writeValueAsBytes(value));
    } catch (Exception e) {
      throw new BusinessException(ErrorCode.INTERNAL_ERROR, "生成登录凭证失败", e);
    }
  }

  private String sign(String content) {
    try {
      Mac mac = Mac.getInstance(HMAC_ALGORITHM);
      mac.init(new SecretKeySpec(
          authProperties.getTokenSecret().getBytes(StandardCharsets.UTF_8),
          HMAC_ALGORITHM
      ));
      return URL_ENCODER.encodeToString(mac.doFinal(content.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new BusinessException(ErrorCode.INTERNAL_ERROR, "生成登录签名失败", e);
    }
  }

  private boolean constantTimeEquals(String expected, String actual) {
    byte[] expectedBytes = expected.getBytes(StandardCharsets.UTF_8);
    byte[] actualBytes = actual.getBytes(StandardCharsets.UTF_8);
    if (expectedBytes.length != actualBytes.length) {
      return false;
    }
    int diff = 0;
    for (int i = 0; i < expectedBytes.length; i++) {
      diff |= expectedBytes[i] ^ actualBytes[i];
    }
    return diff == 0;
  }

  private long toLong(Object value) {
    if (value instanceof Number number) {
      return number.longValue();
    }
    return Long.parseLong(value.toString());
  }

  public record TokenResult(String token, long expiresAtEpochSecond) {
  }
}
