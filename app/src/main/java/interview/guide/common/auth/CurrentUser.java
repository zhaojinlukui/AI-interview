package interview.guide.common.auth;

import interview.guide.modules.user.model.UserRole;

public record CurrentUser(
    String userId,
    String username,
    UserRole role
) {

  public boolean isAdmin() {
    return role == UserRole.ADMIN;
  }
}
