package interview.guide.modules.voiceinterview.config;

import interview.guide.common.auth.VoiceInterviewAuthHandshakeInterceptor;
import interview.guide.common.config.CorsProperties;
import interview.guide.modules.voiceinterview.handler.VoiceInterviewWebSocketHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;
import org.springframework.web.socket.server.support.HttpSessionHandshakeInterceptor;

/**
 * WebSocket配置
 * 注册语音面试WebSocket处理器，配置握手拦截器（认证+会话），
 * 设置跨域允许来源和消息缓冲区大小
 */
@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketConfigurer {

    private final VoiceInterviewWebSocketHandler voiceInterviewWebSocketHandler; // 语音面试WebSocket处理器
    private final CorsProperties corsProperties; // 跨域配置属性
    private final VoiceInterviewAuthHandshakeInterceptor authHandshakeInterceptor; // WebSocket握手认证拦截器

    /**
     * 注册WebSocket处理器
     * 路径格式：/ws/voice-interview/{sessionId}
     * 握手时先进行认证校验，再进行HTTP会话传递
     */
    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(voiceInterviewWebSocketHandler, "/ws/voice-interview/{sessionId}")
                .addInterceptors(authHandshakeInterceptor, new HttpSessionHandshakeInterceptor())
                .setAllowedOrigins(corsProperties.getAllowedOrigins().split(",")); // 从配置中读取允许的跨域来源
    }

    /**
     * 配置Servlet WebSocket容器
     * 增大消息缓冲区至2MB，支持较大的音频数据包传输
     */
    @Bean
    public ServletServerContainerFactoryBean createWebSocketContainer() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        container.setMaxTextMessageBufferSize(2 * 1024 * 1024); // 文本消息缓冲区2MB
        container.setMaxBinaryMessageBufferSize(2 * 1024 * 1024); // 二进制消息缓冲区2MB
        return container;
    }
}