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

/**
 * 语音面试WebSocket握手认证拦截器
 * 在WebSocket连接建立前进行身份认证和会话归属校验，
 * 从URL查询参数中提取令牌，验证用户身份及对语音面试会话的访问权限
 */
@Component
@RequiredArgsConstructor
public class VoiceInterviewAuthHandshakeInterceptor implements HandshakeInterceptor {

    private final JwtService jwtService; // JWT令牌服务
    private final UserRepository userRepository; // 用户数据访问层
    private final VoiceInterviewSessionRepository sessionRepository; // 语音面试会话数据访问层

    /**
     * WebSocket握手前处理
     * 依次进行：令牌提取与解析 → 用户状态校验 → 会话归属校验
     */
    @Override
    public boolean beforeHandshake(
            ServerHttpRequest request,
            ServerHttpResponse response,
            WebSocketHandler wsHandler,
            Map<String, Object> attributes
    ) {
        try {
            // 从URL查询参数中提取令牌
            String token = extractQueryParam(request.getURI(), "token");
            JwtClaims claims = jwtService.parse(token);
            // 验证用户是否存在、是否被禁用、令牌版本是否匹配
            UserEntity user = userRepository.findById(claims.userId())
                    .orElseThrow(() -> new BusinessException(ErrorCode.TOKEN_INVALID, "登录状态无效"));
            if (!Boolean.TRUE.equals(user.getEnabled()) || user.getTokenVersion() != claims.tokenVersion()) {
                throw new BusinessException(ErrorCode.TOKEN_INVALID, "登录状态无效");
            }
            // 从URL路径中提取会话ID并验证会话归属
            Long sessionId = extractSessionId(request.getURI());
            VoiceInterviewSessionEntity session = sessionRepository.findById(sessionId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.VOICE_SESSION_NOT_FOUND, "会话不存在"));
            // 校验当前用户是否为该语音面试会话的创建者
            if (!user.getId().toString().equals(session.getUserId())) {
                throw new BusinessException(ErrorCode.FORBIDDEN, "无权访问该语音面试会话");
            }
            // 将用户信息存入WebSocket会话属性中，供后续处理使用
            attributes.put("userId", user.getId().toString());
            attributes.put("username", user.getUsername());
            attributes.put("userRole", user.getRole().name());
            return true;
        } catch (BusinessException e) {
            // 认证失败返回401状态码，拒绝握手
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
    }

    /**
     * WebSocket握手后处理（无额外操作）
     */
    @Override
    public void afterHandshake(
            ServerHttpRequest request,
            ServerHttpResponse response,
            WebSocketHandler wsHandler,
            Exception exception
    ) {
    }

    /**
     * 从URI查询参数中提取指定名称的参数值
     */
    private String extractQueryParam(URI uri, String name) {
        String query = uri.getRawQuery();
        if (query == null || query.isBlank()) {
            return null;
        }
        // 按&分割查询参数，查找匹配的参数名并返回值
        return Arrays.stream(query.split("&"))
                .map(part -> part.split("=", 2))
                .filter(parts -> parts.length == 2 && parts[0].equals(name))
                .map(parts -> parts[1])
                .findFirst()
                .orElse(null);
    }

    /**
     * 从URI路径中提取会话ID
     * 路径格式：/ws/voice-interview/{sessionId}
     */
    private Long extractSessionId(URI uri) {
        String path = uri.getPath();
        String value = path.substring(path.lastIndexOf('/') + 1);
        return Long.valueOf(value);
    }
}