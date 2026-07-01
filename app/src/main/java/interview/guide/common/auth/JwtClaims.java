package interview.guide.common.auth;

import interview.guide.modules.user.model.UserRole;

public record JwtClaims(
    Long userId,
    String username,
    UserRole role,
    int tokenVersion,
    long expiresAtEpochSecond
) {
}
