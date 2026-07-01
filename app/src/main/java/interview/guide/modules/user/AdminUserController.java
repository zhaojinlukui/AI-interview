package interview.guide.modules.user;

import interview.guide.common.result.Result;
import interview.guide.modules.user.model.AdminUserDTO;
import interview.guide.modules.user.model.UpdateDisplayNameRequest;
import interview.guide.modules.user.model.UpdateUserPasswordRequest;
import interview.guide.modules.user.service.AdminUserService;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/users")
@RequiredArgsConstructor
public class AdminUserController {

  private final AdminUserService adminUserService;

  @GetMapping
  public Result<List<AdminUserDTO>> listUsers() {
    return Result.success(adminUserService.listUsers());
  }

  @PutMapping("/{id}/password")
  public Result<Void> updatePassword(
      @PathVariable Long id,
      @Valid @RequestBody UpdateUserPasswordRequest request
  ) {
    adminUserService.updatePassword(id, request.newPassword());
    return Result.success();
  }

  @PutMapping("/{id}/display-name")
  public Result<Void> updateDisplayName(
      @PathVariable Long id,
      @Valid @RequestBody UpdateDisplayNameRequest request
  ) {
    adminUserService.updateDisplayName(id, request.displayName());
    return Result.success();
  }
}
