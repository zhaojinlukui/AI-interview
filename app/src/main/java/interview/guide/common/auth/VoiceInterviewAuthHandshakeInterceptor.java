package interview.guide.common.auth;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.user.model.UserEntity;
import interview.guide.modules.user.repository.UserRepository;
import interview.guide.modules.voiceinterview.model.VoiceInterviewSessionEntity;
import interview.guide.modules.voiceinterview.repository.VoiceInterviewSessionRepository;
import java.net.URI;
import java.util.Arrays;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

@Component
@RequiredArgsConstructor
public class VoiceInterviewAuthHandshakeInterceptor implements HandshakeInterceptor {

  private final JwtService jwtService;
  private final UserRepository userRepository;
  private final VoiceInterviewSessionRepository sessionRepository;

  @Override
  public boolean beforeHandshake(
      ServerHttpRequest request,
      ServerHttpResponse response,
      WebSocketHandler wsHandler,
      Map<String, Object> attributes
  ) {
    try {
      String token = extractQueryParam(request.getURI(), "token");
      JwtClaims claims = jwtService.parse(token);
      UserEntity user = userRepository.findById(claims.userId())
          .orElseThrow(() -> new BusinessException(ErrorCode.TOKEN_INVALID, "登录状态无效"));
      if (!Boolean.TRUE.equals(user.getEnabled()) || user.getTokenVersion() != claims.tokenVersion()) {
        throw new BusinessException(ErrorCode.TOKEN_INVALID, "登录状态无效");
      }
      Long sessionId = extractSessionId(request.getURI());
      VoiceInterviewSessionEntity session = sessionRepository.findById(sessionId)
          .orElseThrow(() -> new BusinessException(ErrorCode.VOICE_SESSION_NOT_FOUND, "会话不存在"));
      if (!user.getId().toString().equals(session.getUserId())) {
        throw new BusinessException(ErrorCode.FORBIDDEN, "无权访问该语音面试会话");
      }
      attributes.put("userId", user.getId().toString());
      attributes.put("username", user.getUsername());
      attributes.put("userRole", user.getRole().name());
      return true;
    } catch (BusinessException e) {
      response.setStatusCode(HttpStatus.UNAUTHORIZED);
      return false;
    }
  }

  @Override
  public void afterHandshake(
      ServerHttpRequest request,
      ServerHttpResponse response,
      WebSocketHandler wsHandler,
      Exception exception
  ) {
  }

  private String extractQueryParam(URI uri, String name) {
    String query = uri.getRawQuery();
    if (query == null || query.isBlank()) {
      return null;
    }
    return Arrays.stream(query.split("&"))
        .map(part -> part.split("=", 2))
        .filter(parts -> parts.length == 2 && parts[0].equals(name))
        .map(parts -> parts[1])
        .findFirst()
        .orElse(null);
  }

  private Long extractSessionId(URI uri) {
    String path = uri.getPath();
    String value = path.substring(path.lastIndexOf('/') + 1);
    return Long.valueOf(value);
  }
}
