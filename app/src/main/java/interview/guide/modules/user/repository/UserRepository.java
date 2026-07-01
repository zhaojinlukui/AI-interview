package interview.guide.modules.user.repository;

import interview.guide.modules.user.model.UserEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface UserRepository extends JpaRepository<UserEntity, Long> {

  Optional<UserEntity> findByUsernameIgnoreCase(String username);

  boolean existsByUsernameIgnoreCase(String username);

  List<UserEntity> findAllByOrderByCreatedAtDesc();
}
