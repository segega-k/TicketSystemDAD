package uz.inha.tickets.repo;

import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;
import uz.inha.tickets.domain.RefreshTokenEntity;

public interface RefreshTokenRepository extends JpaRepository<RefreshTokenEntity, UUID> {
    Optional<RefreshTokenEntity> findByTokenHash(String tokenHash);
}
