package interview.guide.modules.aisettings.repository;

import interview.guide.modules.aisettings.model.UserAiSettingsEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface UserAiSettingsRepository extends JpaRepository<UserAiSettingsEntity, Long> {

  Optional<UserAiSettingsEntity> findByUserId(String userId);
}
