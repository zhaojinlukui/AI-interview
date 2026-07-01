package interview.guide.modules.aisettings.repository;

import interview.guide.modules.aisettings.model.SystemAiSettingsEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface SystemAiSettingsRepository extends JpaRepository<SystemAiSettingsEntity, Long> {
}
