package uz.inha.tickets.service;

import static uz.inha.tickets.domain.Enums.Role;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.inha.tickets.domain.RefreshTokenEntity;
import uz.inha.tickets.domain.UserAccount;
import uz.inha.tickets.repo.RefreshTokenRepository;
import uz.inha.tickets.repo.UserRepository;

@Service
public class AuthService {

    final UserRepository users;
    final RefreshTokenRepository tokens;
    final PasswordEncoder enc;
    final JwtService jwt;

    @Value("${app.jwt.refresh-ttl-seconds}")
    long refreshTtl;

    public AuthService(UserRepository u, RefreshTokenRepository t, PasswordEncoder e, JwtService j) {
        users = u;
        tokens = t;
        enc = e;
        jwt = j;
    }

    @Transactional
    public Map<String, Object> register(String email, String password, String role, String name) {
        if (users.existsByEmail(email)) throw DomainException.conflict("email already registered");
        if (role != null && !role.isBlank()) {
            String requested = role.trim().toUpperCase();
            if (!requested.equals("CUSTOMER") && !requested.equals("USER")) {
                throw DomainException.forbidden("role escalation forbidden");
            }
        }
        UserAccount u = users.save(new UserAccount(email, enc.encode(password), Role.CUSTOMER, name));
        return issue(u);
    }

    public Map<String, Object> login(String email, String password) {
        UserAccount u = users.findByEmail(email).orElseThrow(() -> DomainException.unauthorized("invalid credentials"));
        if (!enc.matches(password, u.passwordHash)) throw DomainException.unauthorized("invalid credentials");
        return issue(u);
    }

    @Transactional
    public Map<String, Object> refresh(String refresh) {
        RefreshTokenEntity rt = tokens
            .findByTokenHash(hash(refresh))
            .orElseThrow(() -> DomainException.unauthorized("invalid refresh token"));
        if (!rt.active()) throw DomainException.unauthorized("invalid refresh token");
        rt.revokedAt = Instant.now();
        return issue(rt.user);
    }

    @Transactional
    public void logout(String refresh) {
        tokens.findByTokenHash(hash(refresh)).ifPresent(t -> t.revokedAt = Instant.now());
    }

    Map<String, Object> issue(UserAccount u) {
        String refresh = UUID.randomUUID() + "." + UUID.randomUUID();
        tokens.save(new RefreshTokenEntity(hash(refresh), u, Instant.now().plusSeconds(refreshTtl)));
        Map<String, Object> user = Map.of("id", u.id, "email", u.email, "role", u.role);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("accessToken", jwt.access(u));
        out.put("refreshToken", refresh);
        out.put("tokenType", "Bearer");
        out.put("access_token", out.get("accessToken"));
        out.put("refresh_token", refresh);
        out.put("token_type", "Bearer");
        out.put("expires_in", jwt.accessTtlSeconds());
        out.put("expiresIn", jwt.accessTtlSeconds());
        out.put("user", user);
        return out;
    }

    static String hash(String s) {
        try {
            byte[] b = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte x : b) sb.append(String.format("%02x", x));
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
