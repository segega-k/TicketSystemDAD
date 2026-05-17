package uz.inha.tickets.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import uz.inha.tickets.domain.Enums.Role;
import uz.inha.tickets.domain.UserAccount;
import uz.inha.tickets.repo.UserRepository;

@Component
public class AdminBootstrap implements CommandLineRunner {

    public static final String DEFAULT_ADMIN_EMAIL = "admin@example.com";
    public static final String DEFAULT_ADMIN_PASSWORD = "Admin12345!";
    public static final String DEFAULT_ADMIN_NAME = "System Administrator";

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

    private final UserRepository users;
    private final PasswordEncoder encoder;

    public AdminBootstrap(UserRepository users, PasswordEncoder encoder) {
        this.users = users;
        this.encoder = encoder;
    }

    @Override
    public void run(String... args) {
        users
            .findByEmail(DEFAULT_ADMIN_EMAIL)
            .ifPresentOrElse(
                existing -> {
                    if (existing.role != Role.ADMIN) {
                        existing.role = Role.ADMIN;
                        users.save(existing);
                        log.info("Default admin account role corrected to ADMIN: {}", DEFAULT_ADMIN_EMAIL);
                    }
                },
                () -> {
                    UserAccount admin = new UserAccount(
                        DEFAULT_ADMIN_EMAIL,
                        encoder.encode(DEFAULT_ADMIN_PASSWORD),
                        Role.ADMIN,
                        DEFAULT_ADMIN_NAME
                    );
                    users.save(admin);
                    log.info("Default admin account created: {}", DEFAULT_ADMIN_EMAIL);
                }
            );
    }
}
