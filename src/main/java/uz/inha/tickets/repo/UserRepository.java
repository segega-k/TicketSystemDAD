package uz.inha.tickets.repo;

import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;
import uz.inha.tickets.domain.UserAccount;

public interface UserRepository extends JpaRepository<UserAccount, UUID> {
    Optional<UserAccount> findByEmail(String email);
    boolean existsByEmail(String email);
}
