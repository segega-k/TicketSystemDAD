package uz.inha.tickets.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "refresh_tokens")
public class RefreshTokenEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    public UUID id;

    @Column(nullable = false, unique = true, length = 128)
    public String tokenHash;

    @ManyToOne(optional = false)
    public UserAccount user;

    @Column(nullable = false)
    public Instant expiresAt;

    public Instant revokedAt;

    @Column(nullable = false)
    public Instant createdAt = Instant.now();

    protected RefreshTokenEntity() {}

    public RefreshTokenEntity(String h, UserAccount u, Instant e) {
        tokenHash = h;
        user = u;
        expiresAt = e;
    }

    public boolean active() {
        return revokedAt == null && expiresAt.isAfter(Instant.now());
    }
}
