package interview.guide.common.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.when;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.user.model.UserEntity;
import interview.guide.modules.user.model.UserRole;
import interview.guide.modules.user.repository.UserRepository;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

@ExtendWith(MockitoExtension.class)
class AuthInterceptorTest {

  @Mock
  private JwtService jwtService;

  @Mock
  private UserRepository userRepository;

  @Nested
  @DisplayName("权限路径")
  class AdminPath {

    @Test
    @DisplayName("普通用户可以访问 AI 设置接口")
    void allowsUserToAccessAiSettings() {
      AuthInterceptor interceptor = new AuthInterceptor(jwtService, userRepository);
      MockHttpServletRequest request = authenticatedRequest("/api/settings/ai/model");
      UserEntity user = user(UserRole.USER);
      when(jwtService.parse("token")).thenReturn(claims(user));
      when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));

      boolean allowed = interceptor.preHandle(request, new MockHttpServletResponse(), new Object());

      assertThat(allowed).isTrue();
    }

    @Test
    @DisplayName("普通用户仍不能访问管理端接口")
    void rejectsUserToAccessAdminApi() {
      AuthInterceptor interceptor = new AuthInterceptor(jwtService, userRepository);
      MockHttpServletRequest request = authenticatedRequest("/api/admin/users");
      UserEntity user = user(UserRole.USER);
      when(jwtService.parse("token")).thenReturn(claims(user));
      when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));

      Throwable thrown = catchThrowable(
          () -> interceptor.preHandle(request, new MockHttpServletResponse(), new Object())
      );

      assertThat(thrown)
          .isInstanceOf(BusinessException.class)
          .extracting("code")
          .isEqualTo(ErrorCode.FORBIDDEN.getCode());
    }
  }

  private MockHttpServletRequest authenticatedRequest(String uri) {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
    request.addHeader("Authorization", "Bearer token");
    return request;
  }

  private UserEntity user(UserRole role) {
    return UserEntity.builder()
        .id(100L)
        .username("user")
        .displayName("普通用户")
        .passwordHash("hash")
        .role(role)
        .enabled(true)
        .tokenVersion(0)
        .build();
  }

  private JwtClaims claims(UserEntity user) {
    return new JwtClaims(
        user.getId(),
        user.getUsername(),
        user.getRole(),
        user.getTokenVersion(),
        Instant.now().plusSeconds(600).getEpochSecond()
    );
  }
}
