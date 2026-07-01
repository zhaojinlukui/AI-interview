package interview.guide.modules.user.service;

import interview.guide.common.auth.CurrentUserContext;
import interview.guide.common.auth.JwtService;
import interview.guide.common.auth.JwtService.TokenResult;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.user.model.AuthResponse;
import interview.guide.modules.user.model.AuthUserDTO;
import interview.guide.modules.user.model.LoginRequest;
import interview.guide.modules.user.model.RegisterRequest;
import interview.guide.modules.user.model.UserEntity;
import interview.guide.modules.user.model.UserRole;
import interview.guide.modules.user.repository.UserRepository;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordService passwordService;
    private final JwtService jwtService;
    private final UserMapper userMapper;

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        String username = normalizeUsername(request.username());
        if (userRepository.existsByUsernameIgnoreCase(username)) {
            throw new BusinessException(ErrorCode.USER_ALREADY_EXISTS, "用户名已存在");
        }

        UserEntity user = UserEntity.builder()
                .username(username)
                .displayName(resolveDisplayName(request.displayName(), username))
                .passwordHash(passwordService.hash(request.password()))
                .role(UserRole.USER)
                .enabled(true)
                .build();
        user = userRepository.save(user);
        return buildAuthResponse(user);
    }

    @Transactional
    public AuthResponse login(LoginRequest request) {
        UserEntity user = userRepository.findByUsernameIgnoreCase(normalizeUsername(request.username()))
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.USER_INVALID_CREDENTIALS,
                        "用户名或密码错误"
                ));
        if (!Boolean.TRUE.equals(user.getEnabled())) {
            throw new BusinessException(ErrorCode.USER_DISABLED, "用户已被禁用");
        }
        if (!passwordService.matches(request.password(), user.getPasswordHash())) {
            throw new BusinessException(ErrorCode.USER_INVALID_CREDENTIALS, "用户名或密码错误");
        }
        user.setLastLoginAt(LocalDateTime.now());
        user = userRepository.save(user);
        return buildAuthResponse(user);
    }

    public AuthUserDTO getCurrentUser() {
        Long userId = Long.valueOf(CurrentUserContext.getRequiredUserId());
        UserEntity user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND, "用户不存在"));
        return userMapper.toAuthUserDTO(user);
    }

    private AuthResponse buildAuthResponse(UserEntity user) {
        TokenResult token = jwtService.createToken(user);
        return new AuthResponse(
                token.token(),
                "Bearer",
                token.expiresAtEpochSecond(),
                userMapper.toAuthUserDTO(user)
        );
    }

    private String normalizeUsername(String username) {
        return username == null ? "" : username.trim();
    }

    private String resolveDisplayName(String displayName, String username) {
        return displayName == null || displayName.isBlank() ? username : displayName.trim();
    }
}
