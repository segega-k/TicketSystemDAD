package uz.inha.tickets.domain;

import static uz.inha.tickets.domain.Enums.*;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "users")
public class UserAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    public UUID id;

    @Column(nullable = false, unique = true)
    public String email;

    @JsonIgnore
    @Column(nullable = false)
    public String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    public Role role = Role.CUSTOMER;

    public String displayName;

    @Column(nullable = false)
    public Instant createdAt = Instant.now();

    protected UserAccount() {}

    public UserAccount(String e, String p, Role r, String n) {
        email = e;
        passwordHash = p;
        role = r;
        displayName = n;
    }
}
