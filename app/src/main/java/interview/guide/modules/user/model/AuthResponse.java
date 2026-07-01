package interview.guide.modules.user.model;

public record AuthResponse(
    String accessToken,
    String tokenType,
    long expiresAtEpochSecond,
    AuthUserDTO user
) {
}
